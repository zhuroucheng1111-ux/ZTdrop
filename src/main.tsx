import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./index.css";
import "./reference-ui.css";

// 屏蔽 WebView 浏览器菜单，不阻止应用自己的右键事件继续处理。
document.addEventListener("contextmenu", (event) => event.preventDefault());

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
