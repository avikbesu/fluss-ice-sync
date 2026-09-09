import { useVirtualizer } from "@tanstack/react-virtual";
import { useRef } from "react";
import { Column } from "../../api/nlTypes";

const ROW_HEIGHT_PX = 32;
const VIEWPORT_HEIGHT_PX = 420;

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

/**
 * Row-virtualized -- per the build prompt's "very large result sets
 * (paginate or virtualize the table -- don't render 50k rows into the DOM
 * at once)". Only the rows currently scrolled into view (plus a small
 * overscan) are ever mounted, regardless of how many rows are in [rows].
 *
 * A `<table>` element doesn't virtualize cleanly with absolutely
 * positioned rows (it breaks column sizing), so this uses a CSS-grid
 * div layout instead -- header and body columns share the same
 * `grid-template-columns` so they stay aligned.
 */
export function ResultTable({ columns, rows }: { columns: Column[]; rows: unknown[][] }) {
  const parentRef = useRef<HTMLDivElement>(null);

  const rowVirtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => ROW_HEIGHT_PX,
    overscan: 12,
  });

  const gridTemplateColumns = `repeat(${columns.length}, minmax(140px, 1fr))`;

  return (
    <div className="result-table" role="table" aria-rowcount={rows.length}>
      <div className="result-table-header" style={{ display: "grid", gridTemplateColumns }} role="row">
        {columns.map((col) => (
          <div className="result-table-cell result-table-header-cell" key={col.name} role="columnheader">
            <span className="col-name">{col.name}</span>
            <span className="col-type">{col.type}</span>
          </div>
        ))}
      </div>

      <div ref={parentRef} className="result-table-body" style={{ height: VIEWPORT_HEIGHT_PX, overflow: "auto" }}>
        <div style={{ height: rowVirtualizer.getTotalSize(), position: "relative" }}>
          {rowVirtualizer.getVirtualItems().map((virtualRow) => {
            const row = rows[virtualRow.index];
            return (
              <div
                key={virtualRow.key}
                role="row"
                style={{
                  display: "grid",
                  gridTemplateColumns,
                  position: "absolute",
                  top: 0,
                  left: 0,
                  right: 0,
                  height: virtualRow.size,
                  transform: `translateY(${virtualRow.start}px)`,
                }}
              >
                {row.map((cell, cellIndex) => (
                  <div className="result-table-cell" key={cellIndex} role="cell">
                    {formatCell(cell)}
                  </div>
                ))}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
