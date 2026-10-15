"use strict";

// This mockup follows the current Android screens while keeping the page
// self-contained and free of account-specific screenshots.
const heroPhone = document.querySelector(".illustrative-phone");
const heroTabs = [...document.querySelectorAll(".actual-ui-tabs [role=tab]")];
const heroPanels = {
  search: document.querySelector(".mock-search-panel"),
  layout: document.querySelector(".mock-layout-panel"),
  library: document.querySelector(".mock-library-panel"),
};
const pageTitle = document.getElementById("mock-page-title");
const pageSubtitle = document.getElementById("mock-page-subtitle");
const seatSegment = document.getElementById("mock-seat-segment");
const searchSegment = document.getElementById("mock-search-segment");
const layoutSegment = document.getElementById("mock-layout-segment");
const bottomNavItems = [
  ...document.querySelectorAll(".mock-bottom-nav [data-nav]"),
];
function selectHeroScreenshot(tab) {
  heroTabs.forEach((item) => {
    const selected = item === tab;
    item.setAttribute("aria-selected", String(selected));
    item.tabIndex = selected ? 0 : -1;
  });
  heroPhone.dataset.screen = tab.dataset.screen;
  Object.entries(heroPanels).forEach(([name, panel]) => {
    panel.hidden = name !== tab.dataset.screen;
  });
  const screen = tab.dataset.screen;
  const isLibrary = screen === "library";
  pageTitle.textContent = isLibrary ? "我的图书馆" : "选座";
  pageSubtitle.textContent = isLibrary
    ? "按时间，安排下一次到馆。"
    : "为专注，找一个喜欢的位置。";
  seatSegment.hidden = isLibrary;
  searchSegment.classList.toggle("selected", screen === "search");
  layoutSegment.classList.toggle("selected", screen === "layout");
  bottomNavItems.forEach((item) => {
    item.classList.toggle(
      "active",
      item.dataset.nav === (screen === "layout" ? "search" : screen),
    );
  });
}
heroTabs.forEach((tab, index) => {
  tab.addEventListener("click", () => selectHeroScreenshot(tab));
  tab.addEventListener("keydown", (event) => {
    let next;
    if (event.key === "ArrowRight") next = (index + 1) % heroTabs.length;
    if (event.key === "ArrowLeft")
      next = (index - 1 + heroTabs.length) % heroTabs.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = heroTabs.length - 1;
    if (next === undefined) return;
    event.preventDefault();
    selectHeroScreenshot(heroTabs[next]);
    heroTabs[next].focus();
  });
});

document.querySelectorAll(".theme-picker button").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelectorAll(".theme-picker button").forEach((item) => {
      item.setAttribute("aria-pressed", String(item === button));
    });
    document.querySelector(".theme-preview").dataset.theme =
      button.dataset.theme;
    document.getElementById("theme-name").textContent =
      button.getAttribute("aria-label");
  });
});

const dialog = document.getElementById("image-dialog");
const enlargedImage = document.getElementById("enlarged-image");
document.querySelectorAll(".screenshot-card").forEach((button) => {
  if (!button.dataset.image) return;
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
