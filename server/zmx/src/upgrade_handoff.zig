const standard = @import("std");
const posix = standard.posix;

pub const protocol_version: u32 = 1;
pub const timeout_milliseconds = 10_000;
pub const checkpoint_limit = 64 * 1024 * 1024;

pub const Activation = struct {
    incoming: i32,
    outgoing: i32,
    requester: i32,
    active: *bool,
};

// Runtime ownership is reconstructed separately from serializable metadata.
fn excluded(comptime name: []const u8) bool {
    return comptime for (.{ "cfg", "alloc", "clients", "pty_write_buf", "resume_ring", "history_store", "upgrade_journal" }) |field| {
        if (standard.mem.eql(u8, name, field)) break true;
    } else false;
}

pub fn Metadata(comptime Daemon: type) type {
    @setEvalBranchQuota(20_000);
    const original = @typeInfo(Daemon).@"struct".fields;
    comptime var fields: [original.len]standard.builtin.Type.StructField = undefined;
    comptime var count = 0;
    inline for (original) |field| {
        if (!excluded(field.name)) {
            fields[count] = field;
            count += 1;
        }
    }
    return @Type(.{ .@"struct" = .{
        .layout = .auto,
        .fields = fields[0..count],
        .decls = &.{},
        .is_tuple = false,
    } });
}

pub const Connection = struct {
    descriptor: i32,
    pending: bool,
    initialized: bool,
    mirror: bool,
    incoming: []const u8,
    outgoing: []const u8,
};

pub fn Checkpoint(comptime Daemon: type, comptime Configuration: type) type {
    return struct {
        protocol: u32 = protocol_version,
        terminal_version: []const u8,
        configuration: Configuration,
        metadata: Metadata(Daemon),
        listener: i32,
        requester: i32,
        journal_descriptor: i32,
        journal_length: u64,
        pending_input: []const u8,
        resume_ring: []const u8,
        connections: []Connection,

        pub fn capture(allocator: standard.mem.Allocator, daemon: *Daemon, listener: i32, requester: i32, terminal_version: []const u8) !@This() {
            @setEvalBranchQuota(20_000);
            var metadata: Metadata(Daemon) = undefined;
            inline for (@typeInfo(Metadata(Daemon)).@"struct".fields) |field| {
                @field(metadata, field.name) = @field(daemon, field.name);
            }
            const connections = try allocator.alloc(Connection, daemon.clients.items.len);
            for (connections, daemon.clients.items) |*connection, client| {
                connection.* = .{
                    .descriptor = client.socket_fd,
                    .pending = client.has_pending_output,
                    .initialized = client.did_init,
                    .mirror = client.is_mirror,
                    .incoming = client.read_buf.buf.items[client.read_buf.head..],
                    .outgoing = client.write_buf.items,
                };
            }
            return .{
                .terminal_version = terminal_version,
                .configuration = daemon.cfg.*,
                .metadata = metadata,
                .listener = listener,
                .requester = requester,
                .journal_descriptor = daemon.upgrade_journal.?.descriptor,
                .journal_length = daemon.upgrade_journal.?.bytes_written,
                .pending_input = daemon.pty_write_buf.items,
                .resume_ring = daemon.resume_ring[0..daemon.resume_len],
                .connections = connections,
            };
        }
    };
}

pub fn temporaryFile(allocator: standard.mem.Allocator, directory: []const u8) !standard.fs.File {
    const filename = try standard.fmt.allocPrint(allocator, "{s}/.upgrade-{x}", .{ directory, standard.crypto.random.int(u128) });
    defer allocator.free(filename);
    const checkpoint_file = try standard.fs.createFileAbsolute(filename, .{ .read = true, .exclusive = true, .mode = 0o600 });
    errdefer checkpoint_file.close();
    try standard.fs.deleteFileAbsolute(filename);
    return checkpoint_file;
}

pub fn writeCheckpoint(allocator: standard.mem.Allocator, directory: []const u8, checkpoint: anytype) !standard.fs.File {
    // Serialized metadata is bounded independently of the terminal journal.
    var estimate: usize = checkpoint.pending_input.len + checkpoint.resume_ring.len;
    for (checkpoint.connections) |connection| {
        estimate = standard.math.add(usize, estimate, connection.incoming.len) catch return error.CheckpointTooLarge;
        estimate = standard.math.add(usize, estimate, connection.outgoing.len) catch return error.CheckpointTooLarge;
    }
    if (estimate > checkpoint_limit / 8) return error.CheckpointTooLarge;
    const encoded = try standard.json.Stringify.valueAlloc(allocator, checkpoint, .{ .emit_strings_as_arrays = true });
    defer allocator.free(encoded);
    if (encoded.len > checkpoint_limit) return error.CheckpointTooLarge;
    const checkpoint_file = try temporaryFile(allocator, directory);
    errdefer checkpoint_file.close();
    try checkpoint_file.writeAll(encoded);
    return checkpoint_file;
}

pub fn readCheckpoint(comptime State: type, allocator: standard.mem.Allocator, descriptor: i32) !standard.json.Parsed(State) {
    const attributes = try posix.fstat(descriptor);
    if (attributes.size <= 0 or attributes.size > checkpoint_limit) return error.InvalidCheckpoint;
    const bytes = try allocator.alloc(u8, @intCast(attributes.size));
    defer allocator.free(bytes);
    var consumed: usize = 0;
    while (consumed < bytes.len) {
        const received = try posix.pread(descriptor, bytes[consumed..], consumed);
        if (received == 0) return error.TruncatedCheckpoint;
        consumed += received;
    }
    return standard.json.parseFromSlice(State, allocator, bytes, .{ .allocate = .alloc_always });
}

pub fn inheritDescriptor(descriptor: i32) !void {
    const flags = try posix.fcntl(descriptor, posix.F.GETFD, 0);
    _ = try posix.fcntl(descriptor, posix.F.SETFD, flags & ~@as(usize, posix.FD_CLOEXEC));
}

pub fn waitByte(descriptor: i32, expected: u8, timeout: i32) !void {
    var watching = [_]posix.pollfd{.{ .fd = descriptor, .events = posix.POLL.IN, .revents = 0 }};
    if (try posix.poll(&watching, timeout) == 0) return error.UpgradeTimeout;
    var received: [1]u8 = undefined;
    if (try posix.read(descriptor, &received) != 1 or received[0] != expected) return error.CandidateRejected;
}

pub fn sendByte(descriptor: i32, value: u8) !void {
    if (try posix.write(descriptor, &.{value}) != 1) return error.CandidateDisconnected;
}

extern "c" fn sigpending(mask: *posix.sigset_t) c_int;

pub fn terminationPending() bool {
    var pending = posix.sigemptyset();
    return sigpending(&pending) == 0 and posix.sigismember(&pending, posix.SIG.TERM);
}

pub fn prepareCommit(cancellation: i32) !posix.sigset_t {
    var blocked = posix.sigemptyset();
    posix.sigaddset(&blocked, posix.SIG.TERM);
    var previous: posix.sigset_t = undefined;
    posix.sigprocmask(posix.SIG.BLOCK, &blocked, &previous);
    errdefer posix.sigprocmask(posix.SIG.SETMASK, &previous, null);
    var watching = [_]posix.pollfd{.{ .fd = cancellation, .events = posix.POLL.IN, .revents = 0 }};
    if (try posix.poll(&watching, 0) != 0 or terminationPending()) return error.UpgradeCancelled;
    return previous;
}

pub fn waitForCommit(descriptor: i32) !void {
    // The previous daemon keeps this writer open until it exits. On rollback
    // it kills and reaps the candidate before closing the writer.
    var unexpected: [1]u8 = undefined;
    if (try posix.read(descriptor, &unexpected) != 0) return error.InvalidCommit;
}
