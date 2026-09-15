const standard = @import("std");
const posix = standard.posix;
const handoff = @import("upgrade_handoff.zig");
const journal_module = @import("upgrade_journal.zig");
const protocol = @import("ipc.zig");

pub fn Controller(
    comptime Daemon: type,
    comptime Configuration: type,
    comptime Client: type,
    comptime serve: anytype,
    comptime terminal_version: []const u8,
) type {
    return struct {
        const State = handoff.Checkpoint(Daemon, Configuration);

        pub fn begin(daemon: *Daemon, listener: i32, requester: i32, executable: []const u8, cancellation: i32) !void {
            if (daemon.upgrade_journal == null or !daemon.upgrade_journal.?.available) return error.UpgradeJournalUnavailable;
            if (!standard.fs.path.isAbsolute(executable) or standard.mem.indexOfScalar(u8, executable, 0) != null) return error.AbsoluteExecutableRequired;
            const allocator = daemon.alloc;
            const selected = try standard.fs.openFileAbsolute(executable, .{});
            defer selected.close();
            const attributes = try selected.stat();
            if (attributes.kind != .file or attributes.mode & 0o111 == 0) return error.ExecutableRequired;

            const checkpoint = try State.capture(allocator, daemon, listener, requester, terminal_version);
            defer allocator.free(checkpoint.connections);
            const state_file = try handoff.writeCheckpoint(allocator, daemon.cfg.log_dir, checkpoint);
            defer state_file.close();
            var incoming = try posix.pipe2(.{ .CLOEXEC = true });
            defer {
                posix.close(incoming[0]);
                if (incoming[1] >= 0) posix.close(incoming[1]);
            }
            var outgoing = try posix.pipe2(.{ .CLOEXEC = true });
            defer {
                if (outgoing[0] >= 0) posix.close(outgoing[0]);
                posix.close(outgoing[1]);
            }

            const executable_path = try allocator.dupeZ(u8, executable);
            defer allocator.free(executable_path);
            const state_argument = try standard.fmt.allocPrintSentinel(allocator, "{d}", .{state_file.handle}, 0);
            defer allocator.free(state_argument);
            const incoming_argument = try standard.fmt.allocPrintSentinel(allocator, "{d}", .{outgoing[0]}, 0);
            defer allocator.free(incoming_argument);
            const outgoing_argument = try standard.fmt.allocPrintSentinel(allocator, "{d}", .{incoming[1]}, 0);
            defer allocator.free(outgoing_argument);

            const candidate = try posix.fork();
            if (candidate == 0) {
                _ = posix.setsid() catch posix.exit(125);
                posix.close(incoming[0]);
                posix.close(outgoing[1]);
                const descriptors = .{ state_file.handle, outgoing[0], incoming[1], listener, daemon.pty_fd, checkpoint.journal_descriptor };
                inline for (descriptors) |descriptor| handoff.inheritDescriptor(descriptor) catch posix.exit(125);
                for (checkpoint.connections) |connection| handoff.inheritDescriptor(connection.descriptor) catch posix.exit(125);
                const arguments = [_:null]?[*:0]const u8{
                    executable_path, "__restore-upgrade-v1", state_argument, incoming_argument, outgoing_argument,
                };
                const failure = posix.execvpeZ(executable_path, &arguments, standard.c.environ);
                standard.log.err("candidate execution failed: {s}", .{@errorName(failure)});
                posix.exit(126);
            }
            posix.close(incoming[1]);
            incoming[1] = -1;
            posix.close(outgoing[0]);
            outgoing[0] = -1;

            // No candidate reads terminal or client descriptors before commitment.
            errdefer {
                posix.kill(-candidate, posix.SIG.KILL) catch {};
                posix.kill(candidate, posix.SIG.KILL) catch {};
                _ = posix.waitpid(candidate, 0);
            }
            try handoff.waitByte(incoming[0], 'R', handoff.timeout_milliseconds);
            const previous_mask = try handoff.prepareCommit(cancellation);
            defer posix.sigprocmask(posix.SIG.SETMASK, &previous_mask, null);
            try handoff.sendByte(outgoing[1], 'C');
            try handoff.waitByte(incoming[0], 'A', handoff.timeout_milliseconds);
            if (handoff.terminationPending()) posix.kill(candidate, posix.SIG.TERM) catch {};
            // Descriptors are now owned by the replacement. Running ordinary
            // session cleanup here would terminate the preserved shell.
            posix.exit(0);
        }

        pub fn restore(descriptor: i32, incoming: i32, outgoing: i32) anyerror!void {
            @setEvalBranchQuota(20_000);
            const allocator = standard.heap.c_allocator;
            const parsed = try handoff.readCheckpoint(State, allocator, descriptor);
            defer parsed.deinit();
            posix.close(descriptor);
            const checkpoint = parsed.value;
            if (checkpoint.protocol != handoff.protocol_version or
                !standard.mem.eql(u8, checkpoint.terminal_version, terminal_version)) return error.IncompatibleUpgrade;
            if (checkpoint.connections.len > 4096 or checkpoint.metadata.resume_len != checkpoint.resume_ring.len or
                checkpoint.metadata.resume_len > checkpoint.metadata.output_bytes) return error.InvalidCheckpoint;
            if (checkpoint.resume_ring.len > Daemon.RESUME_RING_BYTES or checkpoint.metadata.resume_head >= Daemon.RESUME_RING_BYTES) return error.InvalidCheckpoint;

            var configuration = checkpoint.configuration;
            var daemon: Daemon = undefined;
            inline for (@typeInfo(handoff.Metadata(Daemon)).@"struct".fields) |field| {
                @field(daemon, field.name) = @field(checkpoint.metadata, field.name);
            }
            daemon.cfg = &configuration;
            daemon.alloc = allocator;
            daemon.clients = .empty;
            daemon.pty_write_buf = .empty;
            daemon.resume_ring = if (checkpoint.resume_ring.len == 0) &.{} else try allocator.alloc(u8, Daemon.RESUME_RING_BYTES);
            if (checkpoint.resume_ring.len > 0) {
                @memcpy(daemon.resume_ring[0..checkpoint.resume_ring.len], checkpoint.resume_ring);
            }
            daemon.socket_path = try allocator.dupe(u8, checkpoint.metadata.socket_path);
            daemon.history_store = .{};
            daemon.upgrade_journal = try journal_module.Journal.restore(checkpoint.journal_descriptor, checkpoint.journal_length);
            try daemon.pty_write_buf.appendSlice(allocator, checkpoint.pending_input);
            var requester_found = false;
            for (checkpoint.connections) |connection| {
                _ = try posix.fcntl(connection.descriptor, posix.F.GETFD, 0);
                const client = try allocator.create(Client);
                client.* = .{
                    .alloc = allocator,
                    .socket_fd = connection.descriptor,
                    .has_pending_output = connection.pending,
                    .did_init = connection.initialized,
                    .is_mirror = connection.mirror,
                    .read_buf = try protocol.SocketBuffer.init(allocator),
                    .write_buf = .empty,
                };
                try client.read_buf.buf.appendSlice(allocator, connection.incoming);
                try client.write_buf.appendSlice(allocator, connection.outgoing);
                try daemon.clients.append(allocator, client);
                requester_found = requester_found or connection.descriptor == checkpoint.requester;
            }
            if (!requester_found) return error.InvalidRequester;
            _ = try posix.fcntl(checkpoint.listener, posix.F.GETFD, 0);
            _ = try posix.fcntl(daemon.pty_fd, posix.F.GETFD, 0);

            var active = false;
            // A candidate that fails before activation must only close its own
            // duplicate descriptors. It must never signal the preserved shell.
            defer if (active) {
                daemon.handleKill();
                standard.fs.deleteFileAbsolute(daemon.socket_path) catch {};
            };
            try serve(&daemon, checkpoint.listener, daemon.pty_fd, handoff.Activation{
                .incoming = incoming,
                .outgoing = outgoing,
                .requester = checkpoint.requester,
                .active = &active,
            });
        }
    };
}
