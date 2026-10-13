/* Mobile presentation for the campus site. Original elements, routes and handlers stay intact. */
(function () {
  'use strict';

  if (window.BuceaMobile) {
    window.BuceaMobile.setEnabled(true);
    return;
  }

  var root = document.documentElement;
  var enabled = true;
  var frame = 0;
  var viewport = document.querySelector('meta[name="viewport"]');
  var originalViewport = viewport ? viewport.getAttribute('content') : null;
  var createdViewport = !viewport;
  var labelledButtons = [];
  var style = document.createElement('style');
  style.id = 'bucea-mobile-style';
  style.textContent = String.raw`
html[data-bucea-mobile="true"] {
  --bm-navy: #172f43;
  --bm-teal: #146d67;
  --bm-paper: #f6f5ef;
  --bm-line: #dce4e2;
  --bm-muted: #63747c;
  background: var(--bm-paper);
  width: 100%;
  min-width: 0;
  overflow-x: hidden;
  -webkit-text-size-adjust: 100%;
}
html[data-bucea-mobile="true"] body {
  margin: 0 !important;
  padding: 0 0 28px !important;
  width: 100% !important;
  min-width: 0 !important;
  height: auto !important;
  overflow: auto !important;
  background: var(--bm-paper) !important;
  color: var(--bm-navy);
  font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Noto Sans CJK SC", sans-serif;
}
html[data-bucea-mobile="true"] a { -webkit-tap-highlight-color: #146d6720; }
html[data-bucea-mobile="true"] .header,
html[data-bucea-mobile="true"] .warp,
html[data-bucea-mobile="true"] .handleBtns {
  width: auto !important;
  max-width: 900px;
  height: auto !important;
  min-height: 0 !important;
  margin: 0 auto !important;
  clear: both;
}
html[data-bucea-mobile="true"] .header {
  box-sizing: border-box;
  padding: 18px 20px 12px;
  overflow: hidden;
  color: var(--bm-navy);
}
html[data-bucea-mobile="true"] .header::before {
  content: "图书馆 · 座位服务";
  display: block;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: .02em;
  margin-bottom: 7px;
}
html[data-bucea-mobile="true"] .header .logo,
html[data-bucea-mobile="true"] .header .logoName { display: none; }
html[data-bucea-mobile="true"] .userInfor { float: none !important; width: auto; }
html[data-bucea-mobile="true"] .userInfor ul { display: flex; flex-wrap: wrap; gap: 4px 14px; }
html[data-bucea-mobile="true"] .userInfor li {
  text-align: left;
  font-size: 12px;
  font-weight: 400;
  color: var(--bm-muted);
  list-style: none;
}
html[data-bucea-mobile="true"] .userInfor strong a {
  color: var(--bm-teal);
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  font-size: 14px;
}
html[data-bucea-mobile="true"] .menu {
  float: none !important;
  width: auto !important;
  height: auto !important;
  min-height: 0 !important;
  margin: 0 0 14px;
  padding: 0 16px 9px;
  border: 0;
  background: none !important;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}
html[data-bucea-mobile="true"] .menu ul { display: flex; gap: 8px; width: max-content; }
html[data-bucea-mobile="true"] .menu li,
html[data-bucea-mobile="true"] .menu li.onHover {
  width: auto !important;
  height: auto !important;
  margin: 0;
  background: none !important;
  flex: 0 0 auto;
  list-style: none;
}
html[data-bucea-mobile="true"] .menu li a,
html[data-bucea-mobile="true"] .menu li a:hover {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  height: auto !important;
  width: auto !important;
  box-sizing: border-box;
  padding: 8px 16px;
  line-height: 1.3;
  font-size: 14px;
  font-weight: 600;
  color: var(--bm-muted);
  border: 1px solid var(--bm-line);
  border-radius: 24px;
  background: white !important;
}
html[data-bucea-mobile="true"] .menu .onHover a {
  background: var(--bm-navy) !important;
  border-color: var(--bm-navy);
  color: white !important;
}
html[data-bucea-mobile="true"] .menu i { display: none; }
html[data-bucea-mobile="true"] .mainContent {
  float: none !important;
  width: auto !important;
  min-width: 0;
  height: auto !important;
  min-height: 300px !important;
  margin: 0 16px;
  padding: 0;
  border: 1px solid var(--bm-line);
  border-radius: 20px;
  background: white !important;
}
html[data-bucea-mobile="true"] .content {
  box-sizing: border-box;
  width: 100% !important;
  padding: 18px 14px !important;
  margin: 0 !important;
  min-width: 0;
}
html[data-bucea-mobile="true"] .selectContent,
html[data-bucea-mobile="true"] .selectSet,
html[data-bucea-mobile="true"] .detailContent,
html[data-bucea-mobile="true"] .reserveTime,
html[data-bucea-mobile="true"] .myReserveList,
html[data-bucea-mobile="true"] .calssList,
html[data-bucea-mobile="true"] .seatList {
  box-sizing: border-box;
  width: 100% !important;
  min-width: 0;
  margin: 0 !important;
  padding: 0 !important;
}
html[data-bucea-mobile="true"] .selectBox {
  width: 100% !important;
  height: auto !important;
  margin: 0 0 10px !important;
  padding: 0 !important;
  clear: both;
}
html[data-bucea-mobile="true"] .selectBox > dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
html[data-bucea-mobile="true"] .selectBox dd {
  float: none !important;
  min-width: 0;
  height: auto !important;
  margin: 0 !important;
}
html[data-bucea-mobile="true"] .select_box168,
html[data-bucea-mobile="true"] .select_box {
  float: none !important;
  width: 100% !important;
}
html[data-bucea-mobile="true"] .select_box168 > a,
html[data-bucea-mobile="true"] .select_box > a {
  box-sizing: border-box;
  min-height: 48px;
  height: auto !important;
  padding: 12px 27px 12px 12px;
  border: 1px solid var(--bm-line);
  border-radius: 12px;
  background: #f9fbfa !important;
  color: var(--bm-navy);
  line-height: 1.5;
  font-size: 14px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
html[data-bucea-mobile="true"] .select_box168 > a::after,
html[data-bucea-mobile="true"] .select_box > a::after {
  content: "⌄";
  position: absolute;
  right: 10px;
  color: var(--bm-teal);
}
html[data-bucea-mobile="true"] .select_box168 p,
html[data-bucea-mobile="true"] .select_box p {
  box-sizing: border-box;
  width: 100% !important;
  max-height: 280px;
  overflow-y: auto;
  padding: 4px;
  border: 1px solid var(--bm-line);
  border-radius: 12px;
  box-shadow: 0 12px 30px #172f4320;
  background: white !important;
}
html[data-bucea-mobile="true"] .select_box168 p a,
html[data-bucea-mobile="true"] .select_box p a {
  box-sizing: border-box;
  height: auto !important;
  min-height: 44px;
  padding: 11px 8px;
  background: white !important;
  color: var(--bm-navy);
  line-height: 1.5;
  border-radius: 8px;
}
html[data-bucea-mobile="true"] .select_box168 p a:hover,
html[data-bucea-mobile="true"] .select_box p a:hover { background: #e4f1ed !important; }
html[data-bucea-mobile="true"] input:not([type="hidden"]),
html[data-bucea-mobile="true"] select,
html[data-bucea-mobile="true"] textarea,
html[data-bucea-mobile="true"] button {
  box-sizing: border-box;
  min-height: 44px;
  max-width: 100%;
  font-family: inherit;
  font-size: 15px;
}
html[data-bucea-mobile="true"] input[type="text"],
html[data-bucea-mobile="true"] input[type="password"],
html[data-bucea-mobile="true"] .seatInput {
  width: 100%;
  padding: 12px;
  border: 1px solid var(--bm-line);
  border-radius: 12px;
  background: #f9fbfa;
  color: var(--bm-navy);
}
html[data-bucea-mobile="true"] input[type="button"],
html[data-bucea-mobile="true"] input[type="submit"],
html[data-bucea-mobile="true"] .seatBtn,
html[data-bucea-mobile="true"] .searchBtn {
  float: none !important;
  width: 100%;
  height: auto !important;
  padding: 12px 14px;
  border: 0;
  border-radius: 12px;
  background: var(--bm-teal) !important;
  color: white;
  line-height: 1.5;
  font-weight: 600;
  cursor: pointer;
}
html[data-bucea-mobile="true"] .passTitle {
  display: block;
  float: none;
  width: auto;
  margin: 0 0 6px;
  text-align: left;
  color: var(--bm-muted);
}
html[data-bucea-mobile="true"] .mainContent input.btnUpdate {
  display: block;
  width: 100% !important;
  max-width: 100%;
  margin: 12px 0 0 !important;
}
html[data-bucea-mobile="true"] .moreBtn,
html[data-bucea-mobile="true"] .closeMB {
  width: auto;
  height: auto;
  min-height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: none !important;
  color: var(--bm-teal);
  font-size: 14px;
}
html[data-bucea-mobile="true"] .moreBtn::after { content: "更多筛选条件 ⌄"; }
html[data-bucea-mobile="true"] .seatTip,
html[data-bucea-mobile="true"] .reserveTime h3 {
  margin: 22px 0 12px !important;
  padding: 0;
  background: none;
  color: var(--bm-navy);
  font-size: 17px;
  font-weight: 700;
  line-height: 1.5;
}
html[data-bucea-mobile="true"] .top20 { margin-top: 0 !important; }
html[data-bucea-mobile="true"] .tipContent,
html[data-bucea-mobile="true"] .tipContentred {
  box-sizing: border-box;
  width: auto !important;
  height: auto !important;
  min-height: 48px;
  position: relative !important;
  left: auto !important;
  top: auto !important;
  margin: 12px 0 !important;
  padding: 12px;
  border: 1px solid #bcd5cc;
  border-radius: 12px;
  color: var(--bm-teal);
  background: #eff7f3;
  line-height: 1.6;
  font-size: 13px;
  text-align: left;
}
html[data-bucea-mobile="true"] .tipContentred { border-color: #dfaea8; background: #fff4f1; color: #a6453b; }
html[data-bucea-mobile="true"] .tipContent > div { position: static !important; width: auto !important; height: auto !important; font-size: 13px !important; }
html[data-bucea-mobile="true"] .scrollPic { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; width: 100% !important; }
html[data-bucea-mobile="true"] .scrollable {
  grid-column: 1 / -1;
  grid-row: 1;
  width: 100% !important;
  height: var(--bm-list-height, 320px) !important;
  margin: 0 !important;
  overflow: hidden;
}
/* Keep the site's paging container and seek handlers. Its pages use the live viewport width. */
html[data-bucea-mobile="true"] .scrollable .items .item {
  box-sizing: border-box;
  width: var(--bm-page-width, 280px) !important;
  display: grid !important;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding: 0 0 4px;
}
html[data-bucea-mobile="true"] .scrollable .items .item li,
html[data-bucea-mobile="true"] #favSeatList .items .item li {
  box-sizing: border-box;
  float: none !important;
  width: auto !important;
  min-width: 0;
  height: auto !important;
  min-height: 92px;
  margin: 0 !important;
  padding: 12px 3px 8px;
  border: 1px solid var(--bm-line);
  border-radius: 12px;
  background: #f9fbfa;
}
html[data-bucea-mobile="true"] .scrollable .items .item dd,
html[data-bucea-mobile="true"] #favSeatList .items .item dd { min-height: 44px; height: auto !important; padding-top: 5px; overflow-wrap: anywhere; }
html[data-bucea-mobile="true"] .scrollable .items .item img,
html[data-bucea-mobile="true"] #favSeatList .items .item img { box-sizing: border-box; max-width: 100%; height: auto; }
html[data-bucea-mobile="true"] .scrollPic a.prev,
html[data-bucea-mobile="true"] .scrollPic a.next {
  position: static;
  float: none !important;
  width: auto;
  height: 44px;
  border: 1px solid var(--bm-line);
  border-radius: 12px;
  background: #f4f8f6 !important;
  color: var(--bm-teal);
  text-align: center;
  line-height: 44px;
  font-size: 14px;
}
html[data-bucea-mobile="true"] .scrollPic a.prev::after { content: "← 上一页"; }
html[data-bucea-mobile="true"] .scrollPic a.next::after { content: "下一页 →"; }
html[data-bucea-mobile="true"] .scrollPic a.disabled { opacity: .4; }
html[data-bucea-mobile="true"] #favSeatList { height: auto !important; min-height: 160px; overflow: visible; }
html[data-bucea-mobile="true"] #favSeatList .items { position: static; width: 100%; }
html[data-bucea-mobile="true"] #favSeatList .items .item { box-sizing: border-box; width: 100% !important; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; float: none; }
html[data-bucea-mobile="true"] .reserveTime ul { box-sizing: border-box; margin: 0; padding: 0; width: 100%; }
html[data-bucea-mobile="true"] .reserveTime li { box-sizing: border-box; float: none; width: 100%; height: auto; margin: 0 0 10px; }
html[data-bucea-mobile="true"] .reserveTime li a,
html[data-bucea-mobile="true"] .roomItem .roomBtn {
  box-sizing: border-box;
  width: 100% !important;
  min-height: 56px;
  height: auto !important;
  padding: 12px;
  border: 1px solid var(--bm-line);
  border-radius: 12px;
  background: #eff7f3 !important;
  color: var(--bm-teal);
  line-height: 1.5;
  font-size: 16px;
}
html[data-bucea-mobile="true"] .myReserveList dl:not(#moreBlock) {
  margin: 0 0 12px;
  padding: 14px;
  border: 1px solid var(--bm-line);
  border-radius: 14px;
  background: #f9fbfa;
  overflow-wrap: anywhere;
}
html[data-bucea-mobile="true"] .record,
html[data-bucea-mobile="true"] .msgBoxDIV {
  box-sizing: border-box;
  position: static !important;
  width: auto;
  max-width: 100%;
  margin: 0 16px 12px;
  padding: 12px;
  border-radius: 12px;
  background: var(--bm-teal);
  overflow-wrap: anywhere;
}
html[data-bucea-mobile="true"] .layoutSeat {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  margin: 0;
  padding: 12px 0;
  overflow-wrap: anywhere;
}
html[data-bucea-mobile="true"] .layoutSeat dt { height: auto; line-height: 1.7; padding: 0 0 0 44px; font-size: 18px; }
html[data-bucea-mobile="true"] .layoutSeat dd { height: auto; font-size: 15px; }
html[data-bucea-mobile="true"] .myReserveList a.normal,
html[data-bucea-mobile="true"] .myReserveList a.Missed,
html[data-bucea-mobile="true"] .myReserveList a.Canceled,
html[data-bucea-mobile="true"] .myReserveList a.dateUsed {
  box-sizing: border-box;
  position: static !important;
  width: auto;
  min-height: 44px;
  height: auto;
  margin-top: 10px;
  padding: 8px 14px;
  border-radius: 10px;
  background-image: none;
  background-color: var(--bm-teal);
  line-height: 1.6;
}
html[data-bucea-mobile="true"] .mainContent table { display: block; max-width: 100%; overflow-x: auto; -webkit-overflow-scrolling: touch; }
html[data-bucea-mobile="true"] .mainContent th,
html[data-bucea-mobile="true"] .mainContent td { padding: 10px; min-width: 70px; }
html[data-bucea-mobile="true"] .page { display: flex; flex-wrap: wrap; gap: 8px; height: auto; }
html[data-bucea-mobile="true"] .page li { margin: 0; }
html[data-bucea-mobile="true"] .page a,
html[data-bucea-mobile="true"] .myReserveList .more a { display: inline-flex; align-items: center; min-height: 44px; padding: 0 12px; color: var(--bm-teal); }
/* Never set display on dialogs: the site's show/hide state remains authoritative. */
html[data-bucea-mobile="true"] .dialog,
html[data-bucea-mobile="true"] .seatSearch,
html[data-bucea-mobile="true"] .seatTime,
html[data-bucea-mobile="true"] .seatLayoutList,
html[data-bucea-mobile="true"] .ToolTip_show,
html[data-bucea-mobile="true"] .ui-dialog,
html[data-bucea-mobile="true"] .layui-layer:not(.layui-layer-tips):not(.layui-layer-loading) {
  box-sizing: border-box;
  position: fixed !important;
  left: 12px !important;
  right: 12px !important;
  top: 8vh !important;
  width: auto !important;
  max-width: calc(100vw - 24px) !important;
  min-width: 0 !important;
  height: auto !important;
  max-height: 84vh;
  margin: 0 !important;
  padding: 16px;
  overflow: auto !important;
  border: 1px solid var(--bm-line);
  border-radius: 20px;
  background: white;
  box-shadow: 0 24px 80px #172f4340;
}
html[data-bucea-mobile="true"] .loading2 { position: fixed !important; inset: 0 !important; width: 100% !important; height: 100% !important; background: #172f4380; }
html[data-bucea-mobile="true"] .seatTime h6 { float: none !important; box-sizing: border-box; width: auto; height: auto; line-height: 1.6; margin: 0 0 16px; text-indent: 0; }
html[data-bucea-mobile="true"] .seatTimeTop { height: auto !important; }
html[data-bucea-mobile="true"] .startTime,
html[data-bucea-mobile="true"] .endTime { float: none !important; width: 100%; height: auto; margin: 12px 0 !important; }
html[data-bucea-mobile="true"] .startTime h4,
html[data-bucea-mobile="true"] .endTime h4 { float: none; width: auto; height: auto; padding: 8px 12px; background: var(--bm-teal); border-radius: 10px 10px 0 0; }
html[data-bucea-mobile="true"] .startTime dl,
html[data-bucea-mobile="true"] .endTime dl { float: none; box-sizing: border-box; width: 100%; height: auto; max-height: 220px; min-height: 64px; overflow-y: auto; border: 1px solid var(--bm-line); padding: 8px; border-radius: 0 0 10px 10px; }
html[data-bucea-mobile="true"] .startTime ul,
html[data-bucea-mobile="true"] .endTime ul { box-sizing: border-box; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; width: 100% !important; height: auto !important; margin: 0 !important; overflow: visible !important; }
html[data-bucea-mobile="true"] .startTime li,
html[data-bucea-mobile="true"] .endTime li { float: none; width: auto; height: auto; margin: 0; }
html[data-bucea-mobile="true"] .startTime li a,
html[data-bucea-mobile="true"] .endTime li a { box-sizing: border-box; width: 100%; min-height: 44px; padding: 7px 2px; border-radius: 8px; background: #e4f1ed; color: var(--bm-teal); }
html[data-bucea-mobile="true"] .startTime .onstarTime,
html[data-bucea-mobile="true"] .endTime .onEndTime { background: var(--bm-teal) !important; color: white !important; }
html[data-bucea-mobile="true"] .seatTimeFoot { float: none !important; display: flex; gap: 10px; padding-top: 12px; }
html[data-bucea-mobile="true"] a.sureBtn,
html[data-bucea-mobile="true"] a.cancelBtn { flex: 1; float: none; width: auto; min-height: 44px; height: auto; margin: 0 !important; padding: 4px 8px; border-radius: 12px; background: var(--bm-teal); }
html[data-bucea-mobile="true"] a.cancelBtn { background: #e9eeec; color: var(--bm-navy); }
html[data-bucea-mobile="true"] .seatSearch dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
html[data-bucea-mobile="true"] .seatSearch dt { grid-column: 1 / -1; }
html[data-bucea-mobile="true"] .seatSearch dd { float: none; width: auto; height: auto; min-height: 44px; margin: 0; padding: 4px; line-height: 40px; border-radius: 10px; }
html[data-bucea-mobile="true"] .seatSearch a.closeSeat,
html[data-bucea-mobile="true"] .seatSearch a.sureSeat { position: static; box-sizing: border-box; width: auto; height: auto; min-height: 44px; line-height: 44px; border: 0; border-radius: 10px; font-size: 16px; }
html[data-bucea-mobile="true"] .ui_dialog { box-sizing: border-box; width: 100%; height: auto; padding: 30px 0 0; border: 0; }
html[data-bucea-mobile="true"] .ToolTip_show .closeDIV { width: 44px; height: 44px; top: -8px; right: -8px; line-height: 44px; font-size: 13px; background: #edf3ef; color: var(--bm-teal); border-radius: 22px; }
html[data-bucea-mobile="true"] .ToolTip_show .handle { display: flex; gap: 10px; }
html[data-bucea-mobile="true"] .ToolTip_show .handle input { margin: 0; flex: 1; }
html[data-bucea-mobile="true"] .renewal_List { width: 100%; }
html[data-bucea-mobile="true"] .renewal_List li a { min-height: 44px; line-height: 44px; border-radius: 8px; }
html[data-bucea-mobile="true"] .topHandle { float: none; box-sizing: border-box; display: block; padding: 0 16px 12px; margin: 0; }
html[data-bucea-mobile="true"] .topHandle ul { display: flex; flex-wrap: wrap; gap: 8px; }
html[data-bucea-mobile="true"] .topHandle li { float: none; margin: 0; padding: 0; border: 0; }
html[data-bucea-mobile="true"] .topHandle li a { box-sizing: border-box; width: auto; min-height: 44px; height: auto; padding: 10px 14px; line-height: 1.6; border-radius: 12px; background: var(--bm-teal); }
html[data-bucea-mobile="true"] .topHandle li a.disabled { background: #dce4e2; color: #7c8987; }
/* The map's rows, coordinates, transforms, widths and seat sprites are intentionally untouched. */
html[data-bucea-mobile="true"] .seatLayoutList { padding: 14px; overflow: hidden !important; }
html[data-bucea-mobile="true"] .seatLayoutList h5 { float: none !important; box-sizing: border-box; width: auto; height: auto; background: none; color: var(--bm-navy); font-size: 17px; line-height: 1.6; text-indent: 0; padding-right: 44px; }
html[data-bucea-mobile="true"] .seatInfr { float: none; width: auto; height: auto; margin: 10px 0; }
html[data-bucea-mobile="true"] .seatInfr h6 { float: none; width: auto; height: auto; text-align: left; font-size: 12px; line-height: 1.5; }
html[data-bucea-mobile="true"] .seatInfr ul { display: flex; flex-wrap: wrap; gap: 4px 12px; float: none; width: auto; }
html[data-bucea-mobile="true"] .seatInfr li { float: none; width: auto; height: auto; line-height: 32px; font-size: 12px; }
html[data-bucea-mobile="true"] .seatInfr i { margin: 3px 4px 0 0; }
html[data-bucea-mobile="true"] .seatInfr a.quit { position: absolute; top: 10px; right: 10px; float: none; width: 44px; height: 44px; line-height: 44px; background: #edf3ef; color: var(--bm-teal); }
html[data-bucea-mobile="true"] .seatLayoutList .seatLayout {
  box-sizing: border-box;
  float: none;
  width: 100% !important;
  height: 54vh !important;
  max-height: 600px;
  min-height: 220px;
  margin: 8px 0 0;
  overflow: auto !important;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-x pan-y pinch-zoom;
  border: 1px solid var(--bm-line);
  border-radius: 12px;
  background: #f6f8f7;
}
html[data-bucea-mobile="true"] .bucea-map-hint { margin-top: 6px; font-size: 12px; color: var(--bm-muted); }
html:not([data-bucea-mobile="true"]) .bucea-map-hint { display: none; }
@media (min-width: 600px) {
  html[data-bucea-mobile="true"] .selectBox > dl { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  html[data-bucea-mobile="true"] .scrollable .items .item,
  html[data-bucea-mobile="true"] #favSeatList .items .item { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
`;
  (document.head || root).appendChild(style);

  if (!viewport) {
    viewport = document.createElement('meta');
    viewport.name = 'viewport';
    (document.head || root).appendChild(viewport);
  }

  function labelSpriteButtons() {
    document.querySelectorAll('input.searchBtn, input.seatBtn').forEach(function (button) {
      if (button.value.trim() || button.hasAttribute('data-bucea-labelled')) return;
      labelledButtons.push({ node: button, value: button.value });
      button.setAttribute('data-bucea-labelled', 'true');
      button.value = button.classList.contains('seatBtn') ? '按座位号' : '查询';
    });
  }

  function restoreLabels() {
    labelledButtons.forEach(function (entry) {
      if (!entry.node.isConnected) return;
      entry.node.value = entry.value;
      entry.node.removeAttribute('data-bucea-labelled');
    });
    labelledButtons = [];
  }

  function refreshNow() {
    frame = 0;
    if (!enabled) return;
    labelSpriteButtons();
    document.querySelectorAll('.scrollable').forEach(function (scroller) {
      var width = Math.round(scroller.clientWidth);
      if (!width) return;
      var widthValue = width + 'px';
      if (scroller.style.getPropertyValue('--bm-page-width') !== widthValue) {
        scroller.style.setProperty('--bm-page-width', widthValue);
      }
      var pages = scroller.querySelectorAll('.items .item');
      var index = 0;
      if (window.jQuery) {
        var api = window.jQuery(scroller).data('scrollable');
        if (api && typeof api.getIndex === 'function') index = api.getIndex();
      }
      var page = pages[index] || pages[0];
      var heightValue = Math.max(160, page ? page.scrollHeight : 0) + 'px';
      if (scroller.style.getPropertyValue('--bm-list-height') !== heightValue) {
        scroller.style.setProperty('--bm-list-height', heightValue);
      }
    });
    document.querySelectorAll('.seatLayoutList').forEach(function (dialog) {
      if (!dialog.querySelector('.bucea-map-hint')) {
        var hint = document.createElement('p');
        hint.className = 'bucea-map-hint';
        hint.textContent = '左右滑动查看地图 · 双指缩放';
        dialog.appendChild(hint);
      }
    });
  }

  function refresh() {
    if (!frame) frame = window.requestAnimationFrame(refreshNow);
  }

  function setEnabled(value) {
    enabled = Boolean(value);
    if (enabled) {
      root.setAttribute('data-bucea-mobile', 'true');
      if (!viewport.isConnected) (document.head || root).appendChild(viewport);
      viewport.setAttribute('content', 'width=device-width, initial-scale=1');
      refresh();
    } else {
      root.removeAttribute('data-bucea-mobile');
      restoreLabels();
      if (createdViewport) viewport.remove();
      else if (originalViewport === null) viewport.removeAttribute('content');
      else viewport.setAttribute('content', originalViewport);
    }
    return enabled;
  }

  var observer = new MutationObserver(function (mutations) {
    if (!enabled) return;
    // Observe AJAX results and site-driven show/hide changes, without changing either.
    if (mutations.some(function (mutation) {
      return mutation.type === 'childList' || mutation.attributeName === 'style' || mutation.attributeName === 'class';
    })) refresh();
  });
  observer.observe(document.body || root, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['style', 'class']
  });
  window.addEventListener('resize', refresh);
  window.BuceaMobile = {
    setEnabled: setEnabled,
    refresh: refresh,
    isEnabled: function () { return enabled; }
  };
  setEnabled(true);
}());
