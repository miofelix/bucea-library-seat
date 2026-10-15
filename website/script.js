"use strict";

// Both preview areas use the same illustrated Android screens.
const heroPhone = document.querySelector(".illustrative-phone");
const heroTabs = [...document.querySelectorAll(".actual-ui-tabs [role=tab]")];

// The SVG redraws actual seat positions and labels with illustrative statuses.
// Keep this legend aligned with NativeRoomCanvas and the SVG status palette.
const mapSeatStyles = {
  free: { label: "空闲", marker: "空", color: "#087f6d" },
  occupied: { label: "使用中", marker: "用", color: "#b33f32" },
  reserved: { label: "已预约", marker: "约", color: "#94600a" },
  away: { label: "暂离", marker: "离", color: "#754ba5" },
  unavailable: { label: "不可用", marker: "停", color: "#586471" },
  local: { label: "仅限现场", marker: "现", color: "#2756a4" },
};
const mapLegend = heroPhone.querySelector(".mock-map-legend");
Object.values(mapSeatStyles).forEach((style) => {
  const item = document.createElement("span");
  item.style.setProperty("--seat-color", style.color);
  const marker = document.createElement("i");
  marker.textContent = style.marker;
  marker.setAttribute("aria-hidden", "true");
  item.append(marker, document.createTextNode(style.label));
  mapLegend.append(item);
});

const screenDefinitions = {
  home: {
    title: "首页",
    subtitle: "",
    panel: "home",
    nav: "home",
    header: false,
  },
  regions: {
    title: "各区域座位",
    subtitle: "",
    panel: "home",
    nav: "home",
    header: false,
  },
  search: {
    title: "选座",
    subtitle: "为专注，找一个喜欢的位置。",
    panel: "search",
    nav: "search",
    refresh: true,
  },
  layout: {
    title: "选座",
    subtitle: "为专注，找一个喜欢的位置。",
    panel: "layout",
    nav: "search",
    refresh: true,
  },
  library: {
    title: "我的图书馆",
    subtitle: "按时间，安排下一次到馆。",
    panel: "library",
    nav: "library",
    refresh: true,
  },
  accounts: {
    title: "账号管理",
    subtitle: "你的登录信息，仅保存在本机。",
    panel: "accounts",
    nav: "accounts",
  },
  settings: {
    title: "设置",
    subtitle: "简单一点，刚刚好。",
    panel: "settings",
    nav: "settings",
  },
};

function showIllustration(device, screen) {
  const definition = screenDefinitions[screen];
  device.dataset.screen = screen;
  device.querySelectorAll("[data-screen-panel]").forEach((panel) => {
    panel.hidden = panel.dataset.screenPanel !== definition.panel;
  });
  const title = device.querySelector(".mock-page-title");
  const subtitle = device.querySelector(".mock-subtitle");
  const heading = device.querySelector(".mock-heading");
  title.textContent = definition.title;
  subtitle.textContent = definition.subtitle;
  [heading, title, subtitle].forEach((element) => {
    element.hidden = definition.header === false;
  });
  device.querySelector(".mock-refresh").hidden = !definition.refresh;
  const segment = device.querySelector(".mock-segment");
  segment.hidden = definition.nav !== "search";
  [...segment.children].forEach((item, index) => {
    item.classList.toggle(
      "selected",
      screen === (index === 0 ? "search" : "layout"),
    );
  });
  device.querySelectorAll("[data-nav]").forEach((item) => {
    item.classList.toggle("active", item.dataset.nav === definition.nav);
  });
  const scroller = device.querySelector(".mock-scroll");
  scroller.tabIndex = screen === "layout" ? -1 : 0;
  scroller.setAttribute(
    "aria-label",
    `${definition.title}界面示意，可上下滚动`,
  );
  scroller.scrollTop = 0;
  if (screen === "regions") {
    const regions = device.querySelector(".mock-region-section");
    scroller.scrollTop = regions.offsetTop - scroller.offsetTop - 12;
  }
  if (screen === "layout") {
    const layout = device.querySelector(".mock-layout-panel");
    layout.scrollTop = 0;
  }
}

function selectHeroScreenshot(tab) {
  heroTabs.forEach((item) => {
    const selected = item === tab;
    item.setAttribute("aria-selected", String(selected));
    item.tabIndex = selected ? 0 : -1;
  });
  showIllustration(heroPhone, tab.dataset.screen);
  heroPhone.setAttribute("aria-labelledby", tab.id);
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
selectHeroScreenshot(
  heroTabs.find((tab) => tab.getAttribute("aria-selected") === "true"),
);

// Scale the same device markup for the gallery instead of drawing it twice.
document.querySelectorAll("[data-preview]").forEach((preview) => {
  const screen = preview.dataset.preview;
  const device = heroPhone.cloneNode(true);
  device.className = "mini-app";
  device.removeAttribute("id");
  device.removeAttribute("role");
  device.removeAttribute("aria-labelledby");
  device
    .querySelectorAll("[id]")
    .forEach((element) => element.removeAttribute("id"));
  device.querySelectorAll("[data-screen-panel]").forEach((panel) => {
    if (panel.dataset.screenPanel !== screenDefinitions[screen].panel)
      panel.remove();
  });
  device
    .querySelector(".mock-bottom-nav")
    .setAttribute(
      "aria-label",
      `${screenDefinitions[screen].title}示意底部导航`,
    );
  preview.append(device);
  showIllustration(device, screen);
});

const gallery = document.getElementById("screen-gallery");
const galleryPrevious = document.querySelector("[data-gallery-prev]");
const galleryNext = document.querySelector("[data-gallery-next]");
function updateGalleryControls() {
  galleryPrevious.disabled = gallery.scrollLeft <= 1;
  galleryNext.disabled =
    gallery.scrollLeft + gallery.clientWidth >= gallery.scrollWidth - 1;
}
function moveGallery(direction) {
  const gap = parseFloat(getComputedStyle(gallery).gap) || 0;
  const step =
    gallery.querySelector(".screenshot-card").getBoundingClientRect().width +
    gap;
  gallery.scrollBy({
    left: direction * step,
    behavior: matchMedia("(prefers-reduced-motion: reduce)").matches
      ? "instant"
      : "smooth",
  });
}
galleryPrevious.addEventListener("click", () => moveGallery(-1));
galleryNext.addEventListener("click", () => moveGallery(1));
gallery.addEventListener("scroll", updateGalleryControls, { passive: true });
window.addEventListener("resize", updateGalleryControls);
gallery.addEventListener("keydown", (event) => {
  if (event.target !== gallery) return;
  if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
    event.preventDefault();
    moveGallery(event.key === "ArrowLeft" ? -1 : 1);
  } else if (event.key === "Home" || event.key === "End") {
    event.preventDefault();
    gallery.scrollTo({
      left: event.key === "Home" ? 0 : gallery.scrollWidth,
      behavior: "instant",
    });
  }
});
updateGalleryControls();

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
