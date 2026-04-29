(function () {
  let hoverPopover;
  let clickPopover;
  let boundGrids = new WeakSet();
  let activeHoverCell = null;
  let activeClickCell = null;

  function ensurePopover(kind) {
    const existing = kind === "hover" ? hoverPopover : clickPopover;
    if (existing) {
      return existing;
    }

    const popover = document.createElement("div");
    popover.className = `card-popover card-popover--${kind}`;
    popover.setAttribute("popover", kind === "hover" ? "manual" : "auto");
    document.body.appendChild(popover);

    if (kind === "hover") {
      hoverPopover = popover;
    } else {
      clickPopover = popover;
    }
    return popover;
  }

  function buildLines(cell) {
    const cardId = cell.dataset.cardId;
    const status = cell.dataset.status;
    if (status === "unobtainable") {
      return [`${cardId}`, "Unobtainable"];
    }
    if (status === "unacquired") {
      return [`${cardId}`, "Not acquired"];
    }

    const lines = [
      `${cardId}`,
      `Acquired by: ${cell.dataset.playerName}`,
      `Source: ${cell.dataset.source}`
    ];
    if (cell.dataset.opponent) {
      lines.push(`Opponent: ${cell.dataset.opponent}`);
    }
    lines.push(`At: ${cell.dataset.acquisitionTime}`);
    return lines;
  }

  function renderPopover(popover, cell) {
    popover.innerHTML = "";
    for (const line of buildLines(cell)) {
      const lineElement = document.createElement("div");
      lineElement.className = "card-popover__line";
      lineElement.textContent = line;
      popover.appendChild(lineElement);
    }
  }

  function placePopover(popover, cell) {
    const rect = cell.getBoundingClientRect();
    const viewportWidth = document.documentElement.clientWidth;
    const top = Math.min(window.innerHeight - 24, rect.bottom + 10);
    const left = Math.min(viewportWidth - 24, rect.left + rect.width / 2);
    popover.style.top = `${top}px`;
    popover.style.left = `${left}px`;
    popover.style.transform = "translate(-50%, 0)";
  }

  function showHover(cell) {
    if (activeClickCell === cell) {
      return;
    }
    activeHoverCell = cell;
    const popover = ensurePopover("hover");
    renderPopover(popover, cell);
    placePopover(popover, cell);
    if (!popover.matches(":popover-open")) {
      popover.showPopover();
    }
  }

  function hideHover() {
    activeHoverCell = null;
    if (hoverPopover && hoverPopover.matches(":popover-open")) {
      hoverPopover.hidePopover();
    }
  }

  function showClick(cell) {
    const popover = ensurePopover("click");
    activeClickCell = cell;
    renderPopover(popover, cell);
    placePopover(popover, cell);
    if (popover.matches(":popover-open")) {
      popover.hidePopover();
    }
    popover.showPopover();
  }

  function closeClick() {
    activeClickCell = null;
    if (clickPopover && clickPopover.matches(":popover-open")) {
      clickPopover.hidePopover();
    }
  }

  function bindGrid(grid) {
    if (boundGrids.has(grid)) {
      return;
    }
    boundGrids.add(grid);

    grid.addEventListener("mouseover", (event) => {
      const cell = event.target.closest(".card-cell");
      if (!cell || !grid.contains(cell)) {
        return;
      }
      if (cell !== activeHoverCell) {
        showHover(cell);
      }
    });

    grid.addEventListener("mouseout", (event) => {
      const fromCell = event.target.closest(".card-cell");
      if (!fromCell || !grid.contains(fromCell)) {
        return;
      }
      const toCell = event.relatedTarget instanceof Element ? event.relatedTarget.closest(".card-cell") : null;
      if (fromCell !== toCell) {
        hideHover();
      }
    });

    grid.addEventListener("click", (event) => {
      const cell = event.target.closest(".card-cell");
      if (!cell || !grid.contains(cell)) {
        return;
      }
      event.preventDefault();
      hideHover();
      showClick(cell);
    });
  }

  window.initCardPopovers = function initCardPopovers() {
    document.querySelectorAll(".js-card-grid").forEach(bindGrid);
  };

  document.addEventListener("click", (event) => {
    if (!clickPopover || !clickPopover.matches(":popover-open")) {
      return;
    }
    if (clickPopover.contains(event.target)) {
      return;
    }
    const clickedCell = event.target.closest(".card-cell");
    if (!clickedCell) {
      closeClick();
    }
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeClick();
      hideHover();
    }
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => {
      if (document.querySelector(".card-cell")) {
        window.initCardPopovers();
      }
    }, { once: true });
  } else if (document.querySelector(".card-cell")) {
    window.initCardPopovers();
  }
})();
