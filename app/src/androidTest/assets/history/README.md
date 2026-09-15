# Terminal history roundtrip fixtures

These synthetic fixtures were captured from the vendored zmx server after the
snapshot history-flush fix. They contain no user terminal data.

Both use a 40-column, 5-row primary screen and a 20-row snapshot tail:

- `numbered`: 400 numbered lines, exercising multiple older-history pages.
- `blank`: numbered content with blank rows, including trailing blank history.

`original.vt` is the original terminal input. `snapshot.vt` is the actual bounded
server snapshot. Each `page-*.json` is a server history-page response, in request
order from the snapshot boundary toward older history.

`RemoteTerminalRenderingTest.serverSnapshotAndPagesRetainEveryPhysicalRow`
replays the original stream into a reference Termux emulator, then independently
replays the snapshot and prepends its history pages through the production
Android path. It compares every physical row, wrapping flag, cell style and
cursor position. This catches rows that appear in serialized bytes but are lost
when subsequent terminal control sequences clear the screen.
