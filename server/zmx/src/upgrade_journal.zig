const standard = @import("std");
const ghostty = @import("ghostty-vt");

const signature = "ZMXJRNL1";
const header_length = 24;
const event_header_length = 12;
const maximum_payload = 64 * 1024;
const output_event = 1;
const resize_event = 2;

pub const Geometry = struct { columns: u16, rows: u16 };

/// Complete terminal input history; unavailable journals never permit upgrade.
/// Descriptors remain CLOEXEC until explicitly inherited during handover.
pub const Journal = struct {
    pub const default_limit: u64 = 256 * 1024 * 1024;

    descriptor: standard.posix.fd_t,
    bytes_written: u64,
    available: bool = true,
    limit: u64 = default_limit,

    pub fn create(directory: []const u8, columns: u16, rows: u16) !Journal {
        if (columns == 0 or rows == 0) return error.InvalidGeometry;
        var parent = try standard.fs.cwd().openDir(directory, .{});
        defer parent.close();
        var filename_storage: [64]u8 = undefined;
        const filename = try standard.fmt.bufPrint(&filename_storage, ".upgrade-{x}.journal", .{standard.crypto.random.int(u128)});
        const journal_file = try parent.createFile(filename, .{ .read = true, .exclusive = true, .mode = 0o600 });
        errdefer journal_file.close();
        try parent.deleteFile(filename);
        var header: [header_length]u8 = @splat(0);
        @memcpy(header[0..8], signature);
        standard.mem.writeInt(u32, header[8..12], 1, .little);
        standard.mem.writeInt(u16, header[12..14], columns, .little);
        standard.mem.writeInt(u16, header[14..16], rows, .little);
        standard.mem.writeInt(u32, header[20..24], standard.hash.Crc32.hash(header[0..20]), .little);
        try journal_file.pwriteAll(&header, 0);
        return .{ .descriptor = journal_file.handle, .bytes_written = header.len };
    }

    pub fn deinit(journal: *Journal) void {
        standard.posix.close(journal.descriptor);
        journal.* = undefined;
    }

    pub fn inspect(descriptor: standard.posix.fd_t, bytes_written: u64) !Geometry {
        if (bytes_written < header_length or bytes_written > default_limit) return error.InvalidJournalLength;
        const journal_file: standard.fs.File = .{ .handle = descriptor };
        const information = try journal_file.stat();
        if (information.kind != .file or information.size != bytes_written) return error.InvalidJournalLength;
        var header: [header_length]u8 = undefined;
        try readExactly(journal_file, &header, 0);
        if (!standard.mem.eql(u8, header[0..8], signature) or
            standard.mem.readInt(u32, header[8..12], .little) != 1 or
            standard.mem.readInt(u32, header[16..20], .little) != 0) return error.UnsupportedJournalVersion;
        if (standard.mem.readInt(u32, header[20..24], .little) != standard.hash.Crc32.hash(header[0..20])) return error.CorruptJournal;
        const geometry: Geometry = .{
            .columns = standard.mem.readInt(u16, header[12..14], .little),
            .rows = standard.mem.readInt(u16, header[14..16], .little),
        };
        if (geometry.columns == 0 or geometry.rows == 0) return error.InvalidGeometry;
        return geometry;
    }

    /// Adopts descriptor ownership. Replay must succeed before session takeover.
    pub fn restore(descriptor: standard.posix.fd_t, bytes_written: u64) !Journal {
        _ = try inspect(descriptor, bytes_written);
        return .{ .descriptor = descriptor, .bytes_written = bytes_written };
    }

    pub fn recordOutput(journal: *Journal, payload: []const u8) void {
        var position: usize = 0;
        while (journal.available and position < payload.len) {
            const ending = position + @min(payload.len - position, maximum_payload);
            journal.recordEvent(output_event, payload[position..ending]);
            position = ending;
        }
    }

    pub fn recordResize(journal: *Journal, columns: u16, rows: u16) void {
        if (columns == 0 or rows == 0) {
            journal.available = false;
            return;
        }
        var payload: [4]u8 = undefined;
        standard.mem.writeInt(u16, payload[0..2], columns, .little);
        standard.mem.writeInt(u16, payload[2..4], rows, .little);
        journal.recordEvent(resize_event, &payload);
    }

    fn recordEvent(journal: *Journal, event_type: u32, payload: []const u8) void {
        if (!journal.available) return;
        const event_length = event_header_length + payload.len;
        if (journal.bytes_written > journal.limit or event_length > journal.limit - journal.bytes_written) {
            journal.available = false;
            return;
        }
        var header: [event_header_length]u8 = undefined;
        standard.mem.writeInt(u32, header[0..4], event_type, .little);
        standard.mem.writeInt(u32, header[4..8], @intCast(payload.len), .little);
        standard.mem.writeInt(u32, header[8..12], eventChecksum(header[0..8], payload), .little);
        const journal_file: standard.fs.File = .{ .handle = journal.descriptor };
        journal_file.pwriteAll(&header, journal.bytes_written) catch {
            journal.available = false;
            return;
        };
        journal_file.pwriteAll(payload, journal.bytes_written + header.len) catch {
            journal.available = false;
            return;
        };
        journal.bytes_written += event_length;
    }

    /// Caller supplies freshly initialized terminal and persistent parser stream.
    /// Positional reads never change offsets shared with the surviving writer.
    pub fn replay(journal: *const Journal, allocator: standard.mem.Allocator, terminal: *ghostty.Terminal, stream: anytype) !void {
        if (!journal.available) return error.JournalUnavailable;
        const geometry = try inspect(journal.descriptor, journal.bytes_written);
        if (terminal.cols != geometry.columns or terminal.rows != geometry.rows) return error.InvalidGeometry;
        const journal_file: standard.fs.File = .{ .handle = journal.descriptor };
        var position: u64 = header_length;
        var payload_storage: [maximum_payload]u8 = undefined;
        while (position < journal.bytes_written) {
            if (journal.bytes_written - position < event_header_length) return error.TruncatedJournal;
            var header: [event_header_length]u8 = undefined;
            try readExactly(journal_file, &header, position);
            position += header.len;
            const event_type = standard.mem.readInt(u32, header[0..4], .little);
            const payload_length = standard.mem.readInt(u32, header[4..8], .little);
            if (payload_length == 0 or payload_length > maximum_payload) return error.InvalidEventLength;
            if (payload_length > journal.bytes_written - position) return error.TruncatedJournal;
            const payload = payload_storage[0..payload_length];
            try readExactly(journal_file, payload, position);
            position += payload.len;
            if (standard.mem.readInt(u32, header[8..12], .little) != eventChecksum(header[0..8], payload)) return error.CorruptJournal;
            switch (event_type) {
                output_event => stream.nextSlice(payload),
                resize_event => {
                    if (payload.len != 4) return error.InvalidEventLength;
                    const columns = standard.mem.readInt(u16, payload[0..2], .little);
                    const row_count = standard.mem.readInt(u16, payload[2..4], .little);
                    if (columns == 0 or row_count == 0) return error.InvalidGeometry;
                    const saved_prompt_redraw = terminal.flags.shell_redraws_prompt;
                    terminal.flags.shell_redraws_prompt = .false;
                    defer terminal.flags.shell_redraws_prompt = saved_prompt_redraw;
                    try terminal.resize(allocator, columns, row_count);
                },
                else => return error.UnsupportedJournalEvent,
            }
        }
    }
};

fn eventChecksum(header: []const u8, payload: []const u8) u32 {
    var checksum = standard.hash.Crc32.init();
    checksum.update(header);
    checksum.update(payload);
    return checksum.final();
}

fn readExactly(journal_file: standard.fs.File, destination: []u8, position: u64) !void {
    if (try journal_file.preadAll(destination, position) != destination.len) return error.TruncatedJournal;
}

fn createTestingJournal() !Journal {
    return Journal.create("/tmp", 20, 5);
}

fn createTestingTerminal() !ghostty.Terminal {
    return ghostty.Terminal.init(standard.testing.allocator, .{ .cols = 20, .rows = 5, .max_scrollback = 1024 * 1024 });
}

fn expectTerminalEqual(expected: *ghostty.Terminal, actual: *ghostty.Terminal) !void {
    var expected_writer = standard.Io.Writer.Allocating.init(standard.testing.allocator);
    defer expected_writer.deinit();
    var actual_writer = standard.Io.Writer.Allocating.init(standard.testing.allocator);
    defer actual_writer.deinit();
    var expected_formatter = ghostty.formatter.TerminalFormatter.init(expected, .vt);
    expected_formatter.extra = .all;
    var actual_formatter = ghostty.formatter.TerminalFormatter.init(actual, .vt);
    actual_formatter.extra = .all;
    try expected_formatter.format(&expected_writer.writer);
    try actual_formatter.format(&actual_writer.writer);
    try standard.testing.expectEqualStrings(expected_writer.writer.buffered(), actual_writer.writer.buffered());
    try standard.testing.expectEqual(expected.screens.active.cursor.x, actual.screens.active.cursor.x);
    try standard.testing.expectEqual(expected.screens.active.cursor.y, actual.screens.active.cursor.y);
}

test "journal replays incomplete UTF8 ANSI alternate screens palette tabs and resize" {
    const allocator = standard.testing.allocator;
    var journal = try createTestingJournal();
    defer journal.deinit();
    var original = try createTestingTerminal();
    defer original.deinit(allocator);
    var original_stream = original.vtStream();
    defer original_stream.deinit();
    const segments = [_][]const u8{
        "primary\r\n\x1b[31mred\x1b[0m\r\n\xe4\xb8",      "\xad\r\n",
        "\x1b[3g\x1b[1;4H\x1bH\x1b]4;1;rgb:12/34/56\x1b", "\\\x1b[?1049hALTERNATE\x1b[2;3H\x1b[38;2;12;",
    };
    for (segments) |segment| {
        original_stream.nextSlice(segment);
        journal.recordOutput(segment);
    }
    const saved_prompt_redraw = original.flags.shell_redraws_prompt;
    original.flags.shell_redraws_prompt = .false;
    try original.resize(allocator, 30, 7);
    original.flags.shell_redraws_prompt = saved_prompt_redraw;
    journal.recordResize(30, 7);
    var restored = try createTestingTerminal();
    defer restored.deinit(allocator);
    var restored_stream = restored.vtStream();
    defer restored_stream.deinit();
    try journal.replay(allocator, &restored, &restored_stream);
    try expectTerminalEqual(&original, &restored);
    for ([_][]const u8{ "34;56m色\x1b[0m", "\x1b[?1049l\r\tTAB\xe4", "\xb8\xad" }) |continuation| {
        original_stream.nextSlice(continuation);
        restored_stream.nextSlice(continuation);
        try expectTerminalEqual(&original, &restored);
    }
}

test "journal replay preserves shared descriptor offset and permits continued recording" {
    const allocator = standard.testing.allocator;
    var journal = try createTestingJournal();
    defer journal.deinit();
    journal.recordOutput("first\r\n");
    const inherited = try standard.posix.dup(journal.descriptor);
    var replacement = try Journal.restore(inherited, journal.bytes_written);
    defer replacement.deinit();
    const journal_file: standard.fs.File = .{ .handle = journal.descriptor };
    try journal_file.seekTo(7);
    var restored = try createTestingTerminal();
    defer restored.deinit(allocator);
    var stream = restored.vtStream();
    defer stream.deinit();
    try replacement.replay(allocator, &restored, &stream);
    try standard.testing.expectEqual(@as(u64, 7), try journal_file.getPos());
    replacement.recordOutput("second");
    try standard.testing.expect(replacement.available);
    var repeated = try createTestingTerminal();
    defer repeated.deinit(allocator);
    var repeated_stream = repeated.vtStream();
    defer repeated_stream.deinit();
    try replacement.replay(allocator, &repeated, &repeated_stream);
    stream.nextSlice("second");
    try expectTerminalEqual(&restored, &repeated);
}

test "journal handover preserves incomplete UTF8 and OSC parser state" {
    const allocator = standard.testing.allocator;
    const fixtures = [_]struct { prefix: []const u8, suffix: []const u8 }{
        .{ .prefix = "prefix\xe4\xb8", .suffix = "\xad suffix" },
        .{ .prefix = "\x1b]4;2;rgb:12/", .suffix = "34/56\x1b\\\x1b[32mgreen" },
    };
    for (fixtures) |fixture| {
        var journal = try createTestingJournal();
        defer journal.deinit();
        journal.recordOutput(fixture.prefix);
        var original = try createTestingTerminal();
        defer original.deinit(allocator);
        var original_stream = original.vtStream();
        defer original_stream.deinit();
        original_stream.nextSlice(fixture.prefix);
        var restored = try createTestingTerminal();
        defer restored.deinit(allocator);
        var restored_stream = restored.vtStream();
        defer restored_stream.deinit();
        try journal.replay(allocator, &restored, &restored_stream);
        original_stream.nextSlice(fixture.suffix);
        restored_stream.nextSlice(fixture.suffix);
        try expectTerminalEqual(&original, &restored);
    }
}

test "journal bounds record size and creates anonymous private storage" {
    const allocator = standard.testing.allocator;
    var journal = try createTestingJournal();
    defer journal.deinit();
    const information = try standard.posix.fstat(journal.descriptor);
    try standard.testing.expectEqual(@as(u64, 0), information.nlink);
    try standard.testing.expectEqual(@as(u32, 0o600), information.mode & 0o777);
    const content = try allocator.alloc(u8, maximum_payload * 2 + 3);
    defer allocator.free(content);
    @memset(content, 'x');
    journal.recordOutput(content);
    try standard.testing.expectEqual(header_length + content.len + 3 * event_header_length, journal.bytes_written);
    var original = try createTestingTerminal();
    defer original.deinit(allocator);
    var original_stream = original.vtStream();
    defer original_stream.deinit();
    original_stream.nextSlice(content);
    var restored = try createTestingTerminal();
    defer restored.deinit(allocator);
    var restored_stream = restored.vtStream();
    defer restored_stream.deinit();
    try journal.replay(allocator, &restored, &restored_stream);
    try expectTerminalEqual(&original, &restored);
}

test "journal stops permanently at disk budget without changing recorded prefix" {
    var journal = try createTestingJournal();
    defer journal.deinit();
    journal.limit = journal.bytes_written + event_header_length + 3;
    journal.recordOutput("one");
    const recorded_length = journal.bytes_written;
    journal.recordOutput("overflow");
    journal.recordResize(80, 24);
    try standard.testing.expect(!journal.available);
    try standard.testing.expectEqual(recorded_length, journal.bytes_written);
    const journal_file: standard.fs.File = .{ .handle = journal.descriptor };
    try standard.testing.expectEqual(recorded_length, (try journal_file.stat()).size);
}

test "journal rejects corrupt payload truncated records and unavailable replay" {
    const allocator = standard.testing.allocator;
    var journal = try createTestingJournal();
    defer journal.deinit();
    journal.recordOutput("original");
    const journal_file: standard.fs.File = .{ .handle = journal.descriptor };
    try journal_file.pwriteAll("X", header_length + event_header_length);
    var terminal = try createTestingTerminal();
    defer terminal.deinit(allocator);
    var stream = terminal.vtStream();
    defer stream.deinit();
    try standard.testing.expectError(error.CorruptJournal, journal.replay(allocator, &terminal, &stream));
    try journal_file.setEndPos(journal.bytes_written - 1);
    try standard.testing.expectError(error.InvalidJournalLength, Journal.inspect(journal.descriptor, journal.bytes_written));
    journal.bytes_written -= 1;
    try standard.testing.expectError(error.TruncatedJournal, journal.replay(allocator, &terminal, &stream));
    journal.available = false;
    try standard.testing.expectError(error.JournalUnavailable, journal.replay(allocator, &terminal, &stream));
}

test "journal rejects invalid header geometry and versions" {
    var journal = try createTestingJournal();
    defer journal.deinit();
    try standard.testing.expectEqual(Geometry{ .columns = 20, .rows = 5 }, try Journal.inspect(journal.descriptor, journal.bytes_written));
    try standard.testing.expectError(error.InvalidGeometry, Journal.create("/tmp", 0, 5));
    const journal_file: standard.fs.File = .{ .handle = journal.descriptor };
    try journal_file.pwriteAll("\x02", 8);
    try standard.testing.expectError(error.UnsupportedJournalVersion, Journal.inspect(journal.descriptor, journal.bytes_written));
}

test "journal write failures disable recording without terminating the session" {
    var journal = try createTestingJournal();
    const writable = journal.descriptor;
    const descriptors = try standard.posix.pipe();
    journal.descriptor = descriptors[1];
    defer journal.deinit();
    defer standard.posix.close(descriptors[0]);
    defer standard.posix.close(writable);
    journal.recordOutput("unwritable");
    try standard.testing.expect(!journal.available);
    try standard.testing.expectEqual(@as(u64, header_length), journal.bytes_written);
}
