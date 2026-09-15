package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.*;

public class TerminalHistoryPrependTest {

    @Test
    public void prependPreservesVisibleRowsAndExistingHistoryCoordinates() {
        TerminalBuffer buffer = new TerminalBuffer(8, 12, 3);
        buffer.setChar(0, 0, 'A', TextStyle.NORMAL);
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        buffer.setChar(0, 0, 'B', TextStyle.NORMAL);
        TerminalRow history = buffer.mLines[buffer.externalToInternalRow(-1)];
        TerminalRow visible = buffer.mLines[buffer.externalToInternalRow(0)];
        int firstVisible = buffer.externalToInternalRow(0);

        assertEquals(2, buffer.prependTranscriptRows(new TerminalRow[]{historyRow('X'), historyRow('Y')}));

        assertEquals(3, buffer.getActiveTranscriptRows());
        assertEquals(firstVisible, buffer.externalToInternalRow(0));
        assertSame(history, buffer.mLines[buffer.externalToInternalRow(-1)]);
        assertSame(visible, buffer.mLines[buffer.externalToInternalRow(0)]);
        assertEquals('X', buffer.copyRow(-3).mText[0]);
        assertEquals('Y', buffer.copyRow(-2).mText[0]);
        assertEquals('A', buffer.copyRow(-1).mText[0]);
        assertEquals('B', buffer.copyRow(0).mText[0]);
        assertEquals("        ", characters(buffer.copyRow(2)));
    }

    @Test
    public void prependRetainsBlankRowsWrappingStylesAndUnicodeWithoutAliasing() {
        TerminalBuffer buffer = new TerminalBuffer(8, 12, 3);
        long emphasis = TextStyle.encode(2, 4, TextStyle.CHARACTER_ATTRIBUTE_BOLD);
        TerminalRow source = new TerminalRow(8, emphasis);
        source.setChar(0, 0x4E2D, emphasis);
        source.setChar(2, 0x1F680, emphasis);
        source.setChar(4, 'e', emphasis);
        source.setChar(4, 0x0301, emphasis);
        source.mLineWrap = true;
        source.mStyle[1] = TextStyle.NORMAL;
        TerminalRow blank = new TerminalRow(8, emphasis);
        String original = characters(source);

        assertEquals(2, buffer.prependTranscriptRows(new TerminalRow[]{blank, source}));
        source.clear(TextStyle.NORMAL);
        blank.setChar(0, 'Z', TextStyle.NORMAL);

        TerminalRow copied = buffer.copyRow(-1);
        assertEquals(original, characters(copied));
        assertTrue(copied.mLineWrap);
        assertEquals(emphasis, copied.getStyle(0));
        assertEquals(TextStyle.NORMAL, copied.getStyle(1));
        assertEquals(emphasis, copied.getStyle(4));
        assertEquals("        ", characters(buffer.copyRow(-2)));
        assertEquals(emphasis, buffer.copyRow(-2).getStyle(7));
        copied.clear(TextStyle.NORMAL);
        assertEquals(original, characters(buffer.copyRow(-1)));
        assertTrue(buffer.getLineWrap(-1));
    }

    @Test
    public void capacityKeepsNearestRequestedRowsAndNeverOverwritesExistingRows() {
        TerminalBuffer buffer = new TerminalBuffer(8, 5, 3);
        buffer.setChar(0, 0, 'A', TextStyle.NORMAL);
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        assertEquals(1, buffer.prependTranscriptRows(new TerminalRow[]{historyRow('X'), historyRow('Y'), historyRow('Z')}));
        assertEquals('Z', buffer.copyRow(-2).mText[0]);
        assertEquals('A', buffer.copyRow(-1).mText[0]);
        TerminalRow oldest = buffer.mLines[buffer.externalToInternalRow(-2)];
        assertEquals(0, buffer.prependTranscriptRows(new TerminalRow[]{historyRow('W')}));
        assertSame(oldest, buffer.mLines[buffer.externalToInternalRow(-2)]);
        assertEquals(2, buffer.getActiveTranscriptRows());
    }

    @Test
    public void prependUsesFreeRingSlotsAcrossArrayBoundary() {
        TerminalBuffer buffer = new TerminalBuffer(8, 10, 3);
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        buffer.clearTranscript();
        buffer.setChar(0, 0, 'V', TextStyle.NORMAL);
        int firstVisible = buffer.externalToInternalRow(0);

        assertEquals(4, buffer.prependTranscriptRows(new TerminalRow[]{historyRow('A'), historyRow('B'), historyRow('C'), historyRow('D')}));
        assertEquals(firstVisible, buffer.externalToInternalRow(0));
        assertEquals('A', buffer.copyRow(-4).mText[0]);
        assertEquals('B', buffer.copyRow(-3).mText[0]);
        assertEquals('C', buffer.copyRow(-2).mText[0]);
        assertEquals('D', buffer.copyRow(-1).mText[0]);
        assertEquals('V', buffer.copyRow(0).mText[0]);
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        assertEquals('A', buffer.copyRow(-5).mText[0]);
        assertEquals('V', buffer.copyRow(-1).mText[0]);
    }

    @Test
    public void invalidPageLeavesHistoryUntouched() {
        TerminalBuffer buffer = new TerminalBuffer(8, 12, 3);
        assertThrows(IllegalArgumentException.class, () -> buffer.prependTranscriptRows(
            new TerminalRow[]{historyRow('A'), new TerminalRow(9, TextStyle.NORMAL)}));
        assertEquals(0, buffer.getActiveTranscriptRows());
        assertEquals("        ", characters(buffer.copyRow(0)));
        assertEquals(0, buffer.prependTranscriptRows(new TerminalRow[0]));
        assertThrows(IllegalArgumentException.class, () -> buffer.copyRow(-1));
        assertThrows(IllegalArgumentException.class, () -> buffer.copyRow(3));
    }

    private static TerminalRow historyRow(char character) {
        TerminalRow result = new TerminalRow(8, TextStyle.NORMAL);
        result.setChar(0, character, TextStyle.NORMAL);
        return result;
    }

    private static String characters(TerminalRow source) {
        return new String(source.mText, 0, source.getSpaceUsed());
    }
}
