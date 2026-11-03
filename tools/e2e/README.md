# 端到端验证工具

用**真实无头浏览器**驱动前端页面，验证那些只有在浏览器里才会暴露的行为：路由守卫、
刷新后的登录态恢复、`localStorage` 读写、组件实际渲染结果、401 的统一登出。

## 为什么是这套方案

本机**没有** Playwright、Puppeteer，也没有浏览器 MCP 工具。但：

- Node **v24** 内置了全局 `WebSocket`，因此零依赖就能讲 Chrome DevTools Protocol；
- 无头 Chrome 已是本项目的既有依赖 —— [`tools/brand/render.js`](../brand/render.js)
  就用它渲染 SVG。

于是不需要引入任何新的运行时或 npm 包。

## 工具清单

| 文件 | 用途 |
|---|---|
| `cdp.mjs` | 极简 CDP 客户端：启动浏览器、连接、`evaluate` / `goto` / `fill` / `click` / `waitFor` / 截图，以及断言汇总 |
| `auth-flow.mjs` | 认证闭环用例（注册、登录、守卫、刷新恢复、令牌失效、退出、404） |
| `dashboard-flow.mjs` | 工作台用例（空态四卡与四组最近记录、三个快捷操作与 query 入口、统计卡口径与工作流五步进度、资源选择与就地拦截、**登录用户数据隔离**、单接口失败的分区容错、响应式三档）；**零模型调用**，默认浏览器端口 9226 |
| `profile-flow.mjs` | 个人中心用例（路由导航、昵称修改与即时同步、头像上传/替换/移除、双侧校验、页内退出） |
| `fixtures.mjs` | 简历测试文件的**动态构造器**（DOCX/PDF/HTML/TXT，含中文；全在内存按格式规范拼装，零二进制入库）。也可单独运行 `node tools/e2e/fixtures.mjs --out ./tmp` 生成一组文件供 curl 验收使用 |
| `resume-flow.mjs` | 简历管理用例（上传 DOCX/HTML/TXT/PDF、前端即时校验、服务端 415/400/413、删除三条后果与幂等、分页显隐与翻页） |
| `analysis-flow.mjs` | 简历 AI 分析用例（经接口取 id 直达分析页〔M20：列表页入口已删除〕、自动发起确认、AiWaiting 等待态、结果渲染、**重新分析生成新版本**、未分析空态、错误态）；**消耗真实模型 token（两次完整分析）** |
| `resume-file-flow.mjs` | 简历原文件下载用例（名称非文件入口——点击不触发打开/下载、右侧操作列的下载按钮、取回内容与上传原文一致、删除后 404） |
| `job-flow.mjs` | 岗位模块用例（新建/编辑弹窗与校验、改描述后 stale 立即为 true〔接口层〕与分析页过期警告条、过期重分析生成新版本、描述未变重分析同样生成新版本、删除）；**消耗 3 次真实模型调用** |
| `job-apply-flow.mjs` | 岗位投递入口用例（M19：弹窗「投递链接」字段的**位置**与格式校验、填链接后列表出现「投递」按钮、**点击实际调用 `window.open(url, '_blank', 'noopener,noreferrer')`**〔脚本覆写 `window.open` 捕获实参〕、编辑回填、清空后按钮消失）；**零模型调用**，默认浏览器端口 9222 |
| `job-status-flow.mjs` | 岗位求职进度用例（M21：默认「待投递」、行内下拉切到「已投递」的成功提示与回显、刷新后持久化、编辑弹窗保存不重置进度）；**零模型调用**，默认浏览器端口 9222（与 job-flow / job-apply-flow 相同——不并行跑） |
| `optimization-flow.mjs` | 简历定向优化用例（导航与空态、新建弹窗的就地拦截〔未分析简历/岗位，不发请求〕、选择已有岗位的完整优化流程、工作台区块渲染、编辑保存不覆盖模型原稿、重新评估、历史版本、列表分数对比、**手动输入 JD 的三步编排**）；**消耗 8 次真实模型调用（预置分析 2 + 主流程 3 + 手动 JD 路径 3）**，默认浏览器端口 9225（M11 起替代原 match-flow） |
| `interview-flow.mjs` | 面试模块用例（创建弹窗校验与出题等待态、**进度分母取实际题数**、逐题作答与评价渲染、重答确认弹窗与**总分即时重算**、报告页总分/总评/分类/查看回答、列表进行中与已完成两态）；**消耗 6 次真实模型调用（出题 1 + 评价 3 + 总评 2）**，默认浏览器端口 9223（便于与优化/岗位流程并行） |
| `agent-flow.mjs` | 求职智能体用例（侧栏入口点亮、空态与示例问题填入、上下文选择「未分析只提示不拦截」、非流式等待态文案、助手气泡与**工具调用折叠条只显示名称与状态**、会话列表新增/切换、刷新后恢复）；**消耗约 2–3 次真实对话调用（含工具内部的分析）**，默认浏览器端口 9224 |
| `captcha-flow.mjs` | 登录图形验证码用例（加载即自动获取、点击图片换图、验证码错误提示与自动换图、失败后保留账号并清空敏感输入、填对后登录成功）；**需后端以 `APP_CAPTCHA_FIXED_TEXT` 启动**——脚本看不到图片内容，只能填约定的固定码，默认浏览器端口 9226 |

## 用法

前后端都启动后（后端 8080、前端 5173）：

```bash
node tools/e2e/auth-flow.mjs
node tools/e2e/dashboard-flow.mjs    # 零模型调用（端口 9226）
node tools/e2e/profile-flow.mjs
node tools/e2e/resume-flow.mjs
node tools/e2e/analysis-flow.mjs      # 消耗真实模型 token
node tools/e2e/resume-file-flow.mjs
node tools/e2e/job-flow.mjs           # 消耗 2 次真实模型调用
node tools/e2e/job-apply-flow.mjs     # 零模型调用（端口 9222，与 job-flow 相同——不并行跑）
node tools/e2e/job-status-flow.mjs    # 零模型调用（端口 9222，同上——不并行跑）
node tools/e2e/optimization-flow.mjs  # 消耗真实模型调用（8 次）
node tools/e2e/interview-flow.mjs     # 消耗 6 次真实模型调用（端口 9223）
node tools/e2e/agent-flow.mjs         # 消耗约 2–3 次真实对话调用（端口 9224）
node tools/e2e/captcha-flow.mjs       # 需后端 APP_CAPTCHA_FIXED_TEXT=Ab12 启动（端口 9226）

# 换地址
node tools/e2e/auth-flow.mjs --base http://localhost:5173 --api http://localhost:8080/api

# 同时输出各步骤截图到目录
node tools/e2e/auth-flow.mjs --shots ./shots
```

全部断言通过时退出码为 0，有失败项时为 1，可直接接进流水线。

测试文件均**动态构造、零二进制入库**：

- `profile-flow.mjs` 的头像在浏览器内用 canvas 生成真实 PNG；
- `resume-flow.mjs` 的简历经 `fixtures.mjs` 在内存中按格式规范拼装（DOCX 为手写
  ZIP 容器、PDF 为最小合法结构），base64 注入 `input[type=file]`；
- 测试数据由脚本结束时打印的清理 SQL 手工清除（后端无删除用户接口）。

## 边界

- **接口层验证不要用这里。** 请求响应结构、状态码、越权防护用 `curl` 更快也更精确，
  记录见《验证记录》（本地 `docs/验证记录.md`）。本工具只补浏览器独有的部分。
- `auth-flow.mjs` 会注册 `e2e_seed_*`、`e2e_new_*` 两个测试用户。后端没有删除用户的
  接口，脚本结束时打印清理用的 SQL，需手工执行。

## 写新用例时的坑

- **给 `v-model` 输入框赋值必须走原生 setter + `input` 事件**，直接改 `el.value`
  Vue 感知不到。`cdp.mjs` 的 `fill()` 已封装——它按**元素类型**取 value setter：
  `<input>` 与 `<textarea>` 的 value 在各自的原型上，用错原型会抛
  `TypeError: Illegal invocation`（填多行文本框须传 `.el-textarea__inner` 选择器）。
- **已登录时导航到 `/login`、`/register` 会被守卫重定向**，脚本若以为能停在那一页，
  后续会在一个空 DOM 上操作、报出难以理解的错误。开跑前先 `localStorage.clear()`——
  但注意 `clear()` 必须在 `goto` 到 http 页面**之后**调用：初始页是 `about:blank`
  （opaque origin），在那里读写 `localStorage` 会被浏览器直接拒绝（`SecurityError`）。
  `launchBrowser` 每次用全新 `user-data-dir`，全新 profile 本就无凭据、通常不需要清。
- **Vue Router 不编码 query 中的 `/`**：实际是 `?redirect=/dashboard`，不是 `%2F`；
  断言 URL 前先 `decodeURIComponent`。
- **脚本赋值不会触发 blur 校验**：`fill()` 只设置 value 并派发事件、不获得焦点，
  依赖 `trigger: 'blur'` 的表单规则不会执行。要验证校验拦截，走真实用户路径
  （点提交按钮触发全量校验）而不是模拟 blur；`maxlength` 也只约束用户输入，
  拦不住脚本赋值（因此不能靠它来断言「输入被截断」）。
- **路由跳转的 URL 更新早于组件渲染**：`location.pathname` 匹配后，异步加载的
  页面组件可能还没挂载（首次访问还叠加 Vite 按需编译），此时 DOM 仍是上一页。
  跳转后要再等一个页面内元素出现，别直接断言内容。
- **el-dialog 用 v-show 控制显隐、DOM 常驻**：不能用 `querySelector` 是否存在判断
  「弹窗是否打开/关闭」，要用可见性（如 `offsetParent !== null`）。
- **页面内 fetch 与页面同源才有正确的 Origin**：跨域请求的 CORS 校验依赖 Origin，
  在 `about:blank` 上直接 evaluate fetch 会被拒；先 `goto` 到前端页面再发请求。
- **`--shots` 截图前要等进入动画结束**：Element Plus 的 `el-tag` 等组件带
  `el-zoom-in-center` 过渡（从缩小态放大），元素刚出现就截图会拍到被裁切/半透明的
  中间态。`job-flow.mjs` 的 `shot()` 已封装"等没有标签处于 `enter-active`"再截图。
- **`el-input-number` 的加减按钮连点不可靠**：同一 tick 内连续 `click()` 多次只生效一次
  （面试用例把题数从默认 5 调到 2 时踩到，三连点后值仍是 5）。改值优先用 `fill()` 写其内部
  输入框（`.el-input-number .el-input__inner`），读值校验不达标再逐次点击并留间隔。
- **列表行内的主操作按钮随状态变化**：写「点某按钮进详情」类断言前，先确认当前状态下该按钮
  真的存在（面试列表「进行中」的行是「继续答题」、「已完成」才是「查看报告」）。需要跳过
  状态时直接按 URL `goto` 到目标页更稳。
- **简历/岗位列表页已无分析入口与状态标签（M20）**：进分析页要经接口层取 id 后直接
  `goto`——`GET /resumes` / `GET /jobs` 按 `created_at` 倒序，新资源在首位；
  `?auto=1` 的自动发起语义在**页面路由**上，与原来的列表按钮跳转等价（不带 auto 即「查看」语义）。
  改列表页的按钮/文案前，先 grep 各脚本的行内按钮集合断言与文案子串断言。
- **`ResourcePicker` 弹窗打开时资源列表仍在异步加载**：`.picker` 容器先渲染，列表项
  `.picker__item` 后到（加载期间是 `.picker__loading` 占位）。等容器出现就点列表项会找不到
  元素（agent-flow 首跑踩到），要按 **item 数量** 等待（`optimization-flow.mjs` / `agent-flow.mjs` 均如此）。
- **`offsetParent` 判不了 SVG 的显隐**：该属性只定义在 `HTMLElement` 上，SVG 元素取到
  `undefined`，`offsetParent !== null` 恒真、断言静默放行（验证侧边栏折叠时踩到）。
  判 SVG 的显隐要用 `getComputedStyle(el).display !== 'none'`。
- **布局断言不要写死视口宽度**：纵向滚动条会占掉视口的一部分（本机无头 Chrome 为 8px），
  同一视口下"内容高过屏幕"与"没高过"的两次测量结果并不相同。宽度期望值要用
  `document.documentElement.clientWidth` 现算（验证内容区铺满时踩到）。
- **dev server 热重载会在脚本运行中清空 DOM**：改着文件验证时，Vite 的热重载可能推送
  整页 reload，`querySelector` 恰好落在重建窗口期就取到 `null`。量测前先
  `waitFor('.content')` 之类的"布局已挂载"断言兜底。
- **可见性要断到真正承载视觉的那个元素**：按钮是个 flex 容器，里面的图标被
  `display: none` 时容器照样可见、照样可点 —— 只断言容器会漏掉「图标消失但功能正常」
  这类 bug（侧边栏折叠开关踩到，用户先发现的）。断图标本身：
  `getComputedStyle(icon).display !== 'none'` 再配 `getBoundingClientRect()` 看尺寸。
- **`waitFor` 的条件表达式里 `??` 不能与 `&&` / `||` 无括号混用**（语法错误）：把
  `(el?.src ?? '')` 这类整串再拼一个 `&&` 前缀守卫会解析失败；浏览器端求值抛错后被当作
  `false`，现象是"条件明明该成立却一直等到超时"，而非报出这个语法错误
  （`captcha-flow.mjs` 踩到）。整串自带括号，并用 `||` 兜底即可。
- 每个用例**失败不中断**：`createChecker()` 会跑完全部断言再汇总，一次拿到所有问题。
