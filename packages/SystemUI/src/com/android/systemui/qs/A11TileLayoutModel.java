/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic first-fit, row-major packing used by both A11 QS and QQS. */
public final class A11TileLayoutModel {

    public static final class Span {
        public final int columns;
        public final int rows;

        public Span(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    public static final class Placement {
        public final int index;
        public final int page;
        public final int row;
        public final int column;
        public final int columnSpan;
        public final int rowSpan;

        Placement(int index, int page, int row, int column, int columnSpan, int rowSpan) {
            this.index = index;
            this.page = page;
            this.row = row;
            this.column = column;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }
    }

    private A11TileLayoutModel() {
    }

    public static List<Placement> pack(List<Span> spans, int columns, int rows) {
        if (columns < 1 || rows < 1 || spans.isEmpty()) {
            return Collections.emptyList();
        }
        final ArrayList<Placement> result = new ArrayList<>(spans.size());
        int page = 0;
        boolean[][] occupied = new boolean[rows][columns];
        for (int index = 0; index < spans.size(); index++) {
            final Span requested = spans.get(index);
            final int columnSpan = Math.max(1, Math.min(columns, requested.columns));
            final int rowSpan = Math.max(1, Math.min(rows, requested.rows));
            int[] cell = findFirstFree(occupied, columns, rows, columnSpan, rowSpan);
            if (cell == null) {
                page++;
                occupied = new boolean[rows][columns];
                cell = findFirstFree(occupied, columns, rows, columnSpan, rowSpan);
            }
            occupy(occupied, cell[0], cell[1], columnSpan, rowSpan);
            result.add(new Placement(
                    index, page, cell[0], cell[1], columnSpan, rowSpan));
        }
        return result;
    }

    private static int[] findFirstFree(
            boolean[][] occupied, int columns, int rows, int columnSpan, int rowSpan) {
        for (int row = 0; row <= rows - rowSpan; row++) {
            for (int column = 0; column <= columns - columnSpan; column++) {
                if (isFree(occupied, row, column, columnSpan, rowSpan)) {
                    return new int[] {row, column};
                }
            }
        }
        return null;
    }

    private static boolean isFree(
            boolean[][] occupied, int row, int column, int columnSpan, int rowSpan) {
        for (int y = row; y < row + rowSpan; y++) {
            for (int x = column; x < column + columnSpan; x++) {
                if (occupied[y][x]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void occupy(
            boolean[][] occupied, int row, int column, int columnSpan, int rowSpan) {
        for (int y = row; y < row + rowSpan; y++) {
            for (int x = column; x < column + columnSpan; x++) {
                occupied[y][x] = true;
            }
        }
    }
}
