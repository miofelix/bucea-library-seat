"use strict";

// This is a local demonstration. No library account or live inventory is used.
const previewCopy = {
  overview: ["今天，也给专注<br>留一点时间。", "去图书馆，找回自己的节奏。"],
  map: ["为专注，<br>找一个喜欢的位置。", "看布局选座，把喜欢的角落找到。"],
  favorites: ["喜欢的位置，<br>下次还坐这里。", "长按收藏，下次不必重新寻找。"],
};
const previewTabs = [...document.querySelectorAll(".phone-tabs [role=tab]")];

function selectPreview(tab) {
  previewTabs.forEach((item) => {
    const selected = item === tab;
    item.setAttribute("aria-selected", String(selected));
    item.tabIndex = selected ? 0 : -1;
    document.getElementById(item.getAttribute("aria-controls")).hidden =
      !selected;
  });
  const [title, subtitle] = previewCopy[tab.dataset.panel];
  document.getElementById("preview-title").innerHTML = title;
  document.getElementById("preview-subtitle").textContent = subtitle;
}

previewTabs.forEach((tab, index) => {
  tab.addEventListener("click", () => selectPreview(tab));
  tab.addEventListener("keydown", (event) => {
    let next;
    if (event.key === "ArrowRight") next = (index + 1) % previewTabs.length;
    if (event.key === "ArrowLeft")
      next = (index - 1 + previewTabs.length) % previewTabs.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = previewTabs.length - 1;
    if (next === undefined) return;
    event.preventDefault();
    selectPreview(previewTabs[next]);
    previewTabs[next].focus();
  });
});

const seatMap = document.getElementById("seat-map");
const occupiedSeats = new Set([2, 5, 8, 9, 14, 19, 22]);
for (let index = 0; index < 24; index += 1) {
  const button = document.createElement("button");
  const number = String(index + 25).padStart(3, "0");
  const occupied = occupiedSeats.has(index);
  button.type = "button";
  button.textContent = number;
  button.disabled = occupied;
  button.setAttribute(
    "aria-label",
    `示例座位 ${number}，${occupied ? "使用中" : "空闲，点击选择"}`,
  );
  if (!occupied) {
    button.setAttribute("aria-pressed", "false");
    button.addEventListener("click", () => {
      seatMap.querySelectorAll("[aria-pressed]").forEach((item) => {
        item.setAttribute("aria-pressed", String(item === button));
      });
      document.getElementById("seat-feedback").textContent =
        `已选示例座位 ${number} · 实际预约请在 App 中完成。`;
    });
  }
  seatMap.append(button);
}

document.querySelectorAll(".theme-picker button").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelectorAll(".theme-picker button").forEach((item) => {
      item.setAttribute("aria-pressed", String(item === button));
    });
    document.querySelector(".phone").dataset.theme = button.dataset.theme;
    document.querySelector(".theme-preview").dataset.theme =
      button.dataset.theme;
    document.getElementById("theme-name").textContent =
      button.getAttribute("aria-label");
  });
});

const dialog = document.getElementById("image-dialog");
const enlargedImage = document.getElementById("enlarged-image");
document.querySelectorAll(".screenshot-card").forEach((button) => {
  button.addEventListener("click", () => {
    enlargedImage.src = button.dataset.image;
    enlargedImage.alt = button.querySelector("img").alt;
    document.getElementById("image-caption").textContent =
      button.dataset.caption;
    dialog.showModal();
  });
});
dialog
  .querySelector(".dialog-close")
  .addEventListener("click", () => dialog.close());
dialog.addEventListener("click", (event) => {
  const bounds = dialog.getBoundingClientRect();
  if (
    event.clientX < bounds.left ||
    event.clientX > bounds.right ||
    event.clientY < bounds.top ||
    event.clientY > bounds.bottom
  )
    dialog.close();
});

let toastTimeout;
const toast = document.getElementById("toast");
function showToast(message) {
  clearTimeout(toastTimeout);
  toast.textContent = message;
  toast.hidden = false;
  toastTimeout = setTimeout(() => {
    toast.hidden = true;
  }, 4000);
}
document.querySelectorAll("[data-copy-group]").forEach((button) => {
  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText("1109482748");
      showToast("群号 1109482748 已复制，打开 QQ 搜索即可加入。");
    } catch {
      showToast("QQ 交流群：1109482748，可长按或选中群号复制。");
    }
  });
});
