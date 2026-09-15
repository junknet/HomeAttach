const standard = @import("std");
const ghostty = @import("ghostty-vt");

pub const Request = extern struct {
    anchor: u64,
    before: u64,
    limit: u32,
    reserved: u32 = 0,
};

pub const Metadata = extern struct {
    anchor: u64 = 0,
    columns: u32 = 0,
    remaining: u32 = 0,
};

const Anchor = struct {
    token: u64,
    pages: *ghostty.PageList,
    boundary: *ghostty.Pin,
    earliest: *ghostty.Pin,
    columns: u32,
    distance: usize,

    fn release(anchor: *Anchor) void {
        anchor.pages.untrackPin(anchor.boundary);
        anchor.pages.untrackPin(anchor.earliest);
    }
};

pub const Store = struct {
    anchors: [8]?Anchor = @splat(null),
    replacement: usize = 0,

    pub fn clear(store: *Store) void {
        for (&store.anchors) |*entry| {
            if (entry.*) |*anchor| anchor.release();
            entry.* = null;
        }
    }

    pub fn capture(store: *Store, terminal: *ghostty.Terminal, tail_rows: u32) !Metadata {
        const pages = &terminal.screens.active.pages;
        const metadata: Metadata = .{ .columns = pages.cols };
        if (terminal.screens.active_key != .primary or tail_rows == 0) return metadata;
        const boundary = pages.getTopLeft(.active).up(tail_rows) orelse return metadata;
        if (boundary.up(1) == null) return metadata;
        const tracked = try pages.trackPin(boundary);
        errdefer pages.untrackPin(tracked);
        const earliest = try pages.trackPin(pages.getTopLeft(.screen));
        const token = standard.crypto.random.intRangeAtMost(u64, 1, standard.math.maxInt(u64));
        const entry = &store.anchors[store.replacement];
        if (entry.*) |*anchor| anchor.release();
        entry.* = .{ .token = token, .pages = pages, .boundary = tracked, .earliest = earliest, .columns = pages.cols, .distance = pages.pointFromPin(.screen, boundary).?.screen.y };
        store.replacement = (store.replacement + 1) % store.anchors.len;
        return .{ .anchor = token, .columns = pages.cols, .remaining = 1 };
    }

    pub fn respond(store: *Store, allocator: standard.mem.Allocator, request: Request) ![]u8 {
        for (&store.anchors) |*entry| {
            if (entry.*) |*anchor| {
                if (anchor.token != request.anchor) continue;
                if (anchor.boundary.garbage or anchor.earliest.garbage or anchor.columns != anchor.pages.cols) break;
                const original_top = anchor.boundary.up(anchor.distance) orelse break;
                if (!original_top.eql(anchor.earliest.*)) break;
                return renderPage(allocator, anchor, request);
            }
        }
        return emptyResponse(allocator, request, "expired");
    }
};

pub fn emptyResponse(allocator: standard.mem.Allocator, request: Request, status: []const u8) ![]u8 {
    return standard.fmt.allocPrint(allocator, "{{\"status\":\"{s}\",\"anchor\":\"{d}\",\"before\":{d},\"next\":{d},\"columns\":0,\"more\":false,\"rows\":[]}}\n", .{ status, request.anchor, request.before, request.before });
}

fn renderPage(allocator: standard.mem.Allocator, anchor: *Anchor, request: Request) ![]u8 {
    const maximum_bytes = 512 * 1024;
    const row_storage = try allocator.alloc(u8, maximum_bytes / 2);
    defer allocator.free(row_storage);
    const encoded_storage = try allocator.alloc(u8, maximum_bytes);
    defer allocator.free(encoded_storage);
    var encoded: standard.Io.Writer = .fixed(encoded_storage);
    var entries: [128][]const u8 = undefined;
    var count: usize = 0;
    const offset = standard.math.cast(usize, request.before) orelse return emptyResponse(allocator, request, "expired");
    var position = anchor.boundary.up(offset) orelse return emptyResponse(allocator, request, "expired");
    const limit = standard.math.clamp(request.limit, 1, entries.len);
    while (count < limit) {
        const previous = position.up(1) orelse break;
        var ending = previous;
        ending.x = @intCast(anchor.columns - 1);
        var formatter = ghostty.formatter.PageListFormatter.init(anchor.pages, .vt);
        formatter.top_left = previous;
        formatter.bottom_right = ending;
        var rendered: standard.Io.Writer = .fixed(row_storage);
        rendered.writeAll("\x1b[0m") catch unreachable;
        formatter.format(&rendered) catch return emptyResponse(allocator, request, "unsupported");
        rendered.end = standard.mem.trimEnd(u8, rendered.buffered(), "\r\n").len;
        rendered.writeAll("\x1b[0m") catch return emptyResponse(allocator, request, "unsupported");
        const row_text = rendered.buffered();
        const starting = encoded.end;
        standard.json.Stringify.value(.{ .text = row_text, .wrapped = previous.rowAndCell().row.wrap }, .{}, &encoded) catch {
            encoded.end = starting;
            break;
        };
        if (encoded.end > maximum_bytes - 1024) {
            encoded.end = starting;
            break;
        }
        entries[count] = encoded.buffered()[starting..];
        count += 1;
        position = previous;
    }
    if (count == 0 and position.up(1) != null) return emptyResponse(allocator, request, "unsupported");
    var response: standard.Io.Writer.Allocating = .init(allocator);
    defer response.deinit();
    try response.writer.print("{{\"status\":\"ok\",\"anchor\":\"{d}\",\"before\":{d},\"next\":{d},\"columns\":{d},\"more\":{},\"rows\":[", .{ anchor.token, request.before, request.before + count, anchor.columns, position.up(1) != null });
    var remaining = count;
    while (remaining > 0) {
        remaining -= 1;
        if (remaining != count - 1) try response.writer.writeByte(',');
        try response.writer.writeAll(entries[remaining]);
    }
    try response.writer.writeAll("]}\n");
    return response.toOwnedSlice();
}

test "history pages retain physical rows and remain stable during output" {
    const allocator = standard.testing.allocator;
    var terminal = try ghostty.Terminal.init(allocator, .{ .cols = 12, .rows = 3, .max_scrollback = 1024 * 1024 });
    defer terminal.deinit(allocator);
    var stream = terminal.vtStream();
    defer stream.deinit();
    stream.nextSlice("first\r\n\x1b[31m中文\x1b[0m\r\n\r\nabcdefghijklmnop\r\nlast\r\ncurrent\r\nlive");
    var store: Store = .{};
    defer store.clear();
    const metadata = try store.capture(&terminal, 1);
    try standard.testing.expect(metadata.anchor != 0);
    const request: Request = .{ .anchor = metadata.anchor, .before = 0, .limit = 128 };
    const initial = try store.respond(allocator, request);
    defer allocator.free(initial);
    const decoded = try standard.json.parseFromSlice(standard.json.Value, allocator, initial, .{});
    defer decoded.deinit();
    const entries = decoded.value.object.get("rows").?.array.items;
    try standard.testing.expectEqual(@as(usize, 4), entries.len);
    try standard.testing.expect(standard.mem.indexOf(u8, entries[0].object.get("text").?.string, "first") != null);
    try standard.testing.expect(standard.mem.indexOf(u8, entries[1].object.get("text").?.string, "中文") != null);
    var restored = try ghostty.Terminal.init(allocator, .{ .cols = 12, .rows = 1 });
    defer restored.deinit(allocator);
    var restoration = restored.vtStream();
    defer restoration.deinit();
    restoration.nextSlice(entries[1].object.get("text").?.string);
    const original_cell = terminal.screens.active.pages.getTopLeft(.screen).down(1).?;
    const restored_cell = restored.screens.active.pages.getTopLeft(.active);
    try standard.testing.expectEqualDeep(original_cell.style(original_cell.rowAndCell().cell), restored_cell.style(restored_cell.rowAndCell().cell));
    try standard.testing.expectEqualStrings("\x1b[0m\x1b[0m", entries[2].object.get("text").?.string);
    try standard.testing.expect(entries[3].object.get("wrapped").?.bool);
    stream.nextSlice("\r\nnew output\r\nmore output");
    const repeated = try store.respond(allocator, request);
    defer allocator.free(repeated);
    try standard.testing.expectEqualStrings(initial, repeated);
    const newer = try store.respond(allocator, .{ .anchor = metadata.anchor, .before = 0, .limit = 2 });
    defer allocator.free(newer);
    const older = try store.respond(allocator, .{ .anchor = metadata.anchor, .before = 2, .limit = 2 });
    defer allocator.free(older);
    try standard.testing.expect(standard.mem.indexOf(u8, newer, "abcdefghijkl") != null);
    try standard.testing.expect(standard.mem.indexOf(u8, older, "first") != null);
    stream.nextSlice("\x1b[3J");
    const expired = try store.respond(allocator, request);
    defer allocator.free(expired);
    try standard.testing.expect(standard.mem.indexOf(u8, expired, "expired") != null);
}

test "history anchor storage remains bounded and clearing releases tracked pins" {
    const allocator = standard.testing.allocator;
    var terminal = try ghostty.Terminal.init(allocator, .{ .cols = 20, .rows = 2 });
    defer terminal.deinit(allocator);
    var stream = terminal.vtStream();
    defer stream.deinit();
    stream.nextSlice("first\r\nsecond\r\nthird\r\nfourth\r\nfifth");
    const baseline = terminal.screens.active.pages.countTrackedPins();
    var store: Store = .{};
    defer store.clear();
    const original = try store.capture(&terminal, 1);
    for (0..16) |_| _ = try store.capture(&terminal, 1);
    try standard.testing.expectEqual(baseline + 16, terminal.screens.active.pages.countTrackedPins());
    const response = try store.respond(allocator, .{ .anchor = original.anchor, .before = 0, .limit = 2 });
    defer allocator.free(response);
    try standard.testing.expect(standard.mem.indexOf(u8, response, "expired") != null);
    store.clear();
    try standard.testing.expectEqual(baseline, terminal.screens.active.pages.countTrackedPins());
}

test "history page byte budget shortens dense styled pages without dropping rows" {
    const allocator = standard.testing.allocator;
    var terminal = try ghostty.Terminal.init(allocator, .{ .cols = 1000, .rows = 2, .max_scrollback = 4 * 1024 * 1024 });
    defer terminal.deinit(allocator);
    var stream = terminal.vtStream();
    defer stream.deinit();
    for (0..132) |_| {
        for (0..500) |_| stream.nextSlice("\x1b[31mA\x1b[32mB");
        stream.nextSlice("\r\n");
    }
    var store: Store = .{};
    defer store.clear();
    const metadata = try store.capture(&terminal, 1);
    const response = try store.respond(allocator, .{ .anchor = metadata.anchor, .before = 0, .limit = 128 });
    defer allocator.free(response);
    try standard.testing.expect(response.len <= 512 * 1024);
    const parsed = try standard.json.parseFromSlice(standard.json.Value, allocator, response, .{});
    defer parsed.deinit();
    const count = parsed.value.object.get("rows").?.array.items.len;
    try standard.testing.expect(count > 0 and count < 128);
    try standard.testing.expectEqual(@as(i64, @intCast(count)), parsed.value.object.get("next").?.integer);
    try standard.testing.expect(parsed.value.object.get("more").?.bool);
}
