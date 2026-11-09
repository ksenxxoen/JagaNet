/**
 * A list whose order is changed by dragging (@hello-pangea/dnd): the order of nodes, later of
 * anything else with an order. It works without a mouse in two ways: the drag handle takes the
 * keyboard (space lifts, the arrows move, space drops, Escape cancels; screen readers are told so
 * in the site's language), and every row has "Move up" and "Move down" buttons.
 *
 * The list does not keep an order of its own: `onReorder` gets the new list and the parent saves
 * it (and shows it at once, see useReorder in the nodes tab).
 *
 *   <SortableList label={t("Order in the list")} items={nodes} itemKey={(n) => n.id} itemLabel={(n) => n.name}
 *                 onReorder={(list) => save(list.map((n) => n.id))} renderItem={(n) => <b>{n.name}</b>} />
 */
import { DragDropContext, Draggable, Droppable, type DropResult } from "@hello-pangea/dnd";
import { ArrowDown, ArrowUp, GripVertical } from "lucide-react";
import { useId, type ReactNode } from "react";
import { Button } from "@/components/ui";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";

/** The list with the item at `from` moved to `to` (a new array; out-of-range moves change nothing). */
export function moveItem<T>(list: readonly T[], from: number, to: number): T[] {
  const out = [...list];
  if (from === to || from < 0 || to < 0 || from >= out.length || to >= out.length) return out;
  const [item] = out.splice(from, 1);
  out.splice(to, 0, item as T);
  return out;
}

export function SortableList<T>({
  items,
  itemKey,
  itemLabel,
  renderItem,
  onReorder,
  label,
  disabled,
  className,
}: {
  items: readonly T[];
  itemKey: (item: T) => string;
  /** The item's name for the buttons ("Move up: Germany 1"). */
  itemLabel: (item: T) => string;
  renderItem: (item: T, index: number) => ReactNode;
  onReorder: (items: T[]) => void;
  /** The list's accessible name. */
  label: string;
  /** While saving: no dragging, the buttons are off. */
  disabled?: boolean;
  className?: string;
}) {
  const { t } = useI18n();
  const id = useId();
  const move = (from: number, to: number) => {
    if (disabled || from === to) return;
    onReorder(moveItem(items, from, to));
  };
  const onDragEnd = (r: DropResult) => {
    if (r.destination) move(r.source.index, r.destination.index);
  };

  return (
    <DragDropContext
      onDragEnd={onDragEnd}
      dragHandleUsageInstructions={t("Press space to pick it up, the arrow keys to move it, space to put it down and Escape to cancel.")}
    >
      <Droppable droppableId={id} isDropDisabled={disabled}>
        {(drop) => (
          <ol
            ref={drop.innerRef}
            {...drop.droppableProps}
            aria-label={label}
            aria-busy={disabled || undefined}
            className={cn("m-0 list-none rounded-panel border border-line bg-surface p-0 shadow-sm", className)}
          >
            {items.map((item, i) => (
              <Draggable key={itemKey(item)} draggableId={itemKey(item)} index={i} isDragDisabled={disabled}>
                {(drag, state) => (
                  <li
                    ref={drag.innerRef}
                    {...drag.draggableProps}
                    className={cn(
                      "flex items-center gap-3 border-b border-line-soft bg-surface px-3 py-2.5 last:border-b-0",
                      state.isDragging && "rounded-ctl border border-line-strong shadow",
                    )}
                  >
                    <span
                      {...drag.dragHandleProps}
                      aria-label={t("Drag {name}", { name: itemLabel(item) })}
                      className={cn(
                        "inline-flex size-8 flex-none items-center justify-center rounded-lg text-faint hover:bg-subtle hover:text-ink-heading",
                        "focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-focus/25",
                        disabled ? "cursor-not-allowed" : "cursor-grab",
                      )}
                    >
                      <GripVertical aria-hidden="true" className="size-4" />
                    </span>
                    <span className="w-6 flex-none text-right text-[13px] text-muted tnum">{i + 1}</span>
                    <div className="min-w-0 flex-1">{renderItem(item, i)}</div>
                    <span className="flex flex-none gap-1">
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={t("Move up: {name}", { name: itemLabel(item) })}
                        disabled={disabled || i === 0}
                        onClick={() => move(i, i - 1)}
                      >
                        <ArrowUp aria-hidden="true" />
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={t("Move down: {name}", { name: itemLabel(item) })}
                        disabled={disabled || i === items.length - 1}
                        onClick={() => move(i, i + 1)}
                      >
                        <ArrowDown aria-hidden="true" />
                      </Button>
                    </span>
                  </li>
                )}
              </Draggable>
            ))}
            {drop.placeholder}
          </ol>
        )}
      </Droppable>
    </DragDropContext>
  );
}
