/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class A11TileLayoutModelTest {

    @Test
    public void defaultPhonePage_matchesReferenceGrid() {
        final List<A11TileLayoutModel.Span> spans = List.of(
                span(2, 1), span(1, 1), span(1, 1),
                span(2, 1), span(1, 1), span(1, 1),
                span(1, 1), span(1, 1), span(1, 2), span(1, 2),
                span(1, 1), span(1, 1));

        final List<A11TileLayoutModel.Placement> result =
                A11TileLayoutModel.pack(spans, 4, 4);

        assertPlacement(result.get(0), 0, 0, 0, 2, 1);
        assertPlacement(result.get(3), 0, 1, 0, 2, 1);
        assertPlacement(result.get(8), 0, 2, 2, 1, 2);
        assertPlacement(result.get(9), 0, 2, 3, 1, 2);
        assertPlacement(result.get(10), 0, 3, 0, 1, 1);
        assertPlacement(result.get(11), 0, 3, 1, 1, 1);
    }

    @Test
    public void overflow_startsStableSecondPage() {
        final List<A11TileLayoutModel.Span> spans = List.of(
                span(2, 1), span(2, 1), span(2, 1), span(2, 1), span(1, 2));

        final List<A11TileLayoutModel.Placement> result =
                A11TileLayoutModel.pack(spans, 4, 2);

        assertEquals(0, result.get(3).page);
        assertPlacement(result.get(4), 1, 0, 0, 1, 2);
    }

    private static A11TileLayoutModel.Span span(int columns, int rows) {
        return new A11TileLayoutModel.Span(columns, rows);
    }

    private static void assertPlacement(
            A11TileLayoutModel.Placement actual,
            int page,
            int row,
            int column,
            int columnSpan,
            int rowSpan) {
        assertEquals(page, actual.page);
        assertEquals(row, actual.row);
        assertEquals(column, actual.column);
        assertEquals(columnSpan, actual.columnSpan);
        assertEquals(rowSpan, actual.rowSpan);
    }
}
