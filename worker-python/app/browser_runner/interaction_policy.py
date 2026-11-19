"""Conservative UI action policy, separate from website transport permissions.

DOM evidence is not a security proof about arbitrary site JavaScript. This policy
assumes a user-trusted website; ambiguous controls still require confirmation.
"""
import re
from urllib.parse import urlsplit

from .engine import origin


EXTERNAL = re.compile(r"发送|提交|发布|删除|清空|移除|付款|支付|转账|购买|下单|授权|邀请|分享|保存|确认|退出|注销|登录|登入|保存|订阅|取消订单|"
                      r"\b(send|submit|publish|delete|remove|erase|pay|payment|transfer|purchase|buy|checkout|authorize|invite|share|save|confirm|logout|login|subscribe)\b", re.I)
NAVIGATION = re.compile(r"^(写信|收件箱|草稿箱|已发送|查看|详情|返回|上一页|下一页|展开|收起|搜索|查询|筛选|预览|"
                        r"compose|inbox|drafts|sent|view|details|back|previous|next|expand|collapse|search|filter|preview)$", re.I)


async def classify(element, request, session):
    if request.action == "assert_text":
        return "VERIFY", "READ_ONLY_ASSERTION"
    evidence = await element.evaluate("""el => {
        const control = el.closest('button,a,input,select,textarea,[role="button"],[role="link"],[role="tab"],[role="menuitem"]') || el;
        return {tag: control.tagName, role: control.getAttribute('role') || '', type: control.type || '',
            label: control.getAttribute('aria-label') || control.innerText || control.value || '',
            href: control.getAttribute('href') === null ? '' : control.href,
            form: !!control.form || !!control.closest('form'), download: control.hasAttribute('download'),
            disabled: !!control.disabled || control.getAttribute('aria-disabled') === 'true',
            cursor: getComputedStyle(control).cursor,
            editable: el.isContentEditable || (el.tagName === 'BODY' && document.designMode === 'on') || ['INPUT','TEXTAREA','SELECT'].includes(el.tagName),
            editableType: el.type || '',
            handler: ['onclick','oninput','onchange'].map(k => control.getAttribute(k) || '').join(' ')};
    }""")
    if evidence["editableType"] in {"password", "file"}:
        raise ValueError("密码和文件上传必须由用户在浏览器窗口中处理")
    if evidence["disabled"]:
        return "UNKNOWN", "DISABLED_CONTROL"
    if request.action in {"fill", "select"}:
        authorized = session.user_authorized or request.target.name in session.config.get("fields", [])
        if authorized and evidence["editable"] and evidence["editableType"] not in {"submit", "button", "reset", "hidden"}:
            if re.search(r"submit\s*\(|requestSubmit\s*\(", evidence["handler"]):
                return "EXTERNAL", "INPUT_SUBMITS_FORM"
            return "PREPARE", "AUTHORIZED_EDITOR_MAY_AUTOSAVE"
        return "UNKNOWN", "UNVERIFIED_EDITOR"
    label = evidence["label"].strip()
    if (EXTERNAL.search(label) and not NAVIGATION.fullmatch(label)) or (EXTERNAL.search(request.target.name) and not NAVIGATION.fullmatch(request.target.name)):
        return "EXTERNAL", "EXTERNAL_OR_DESTRUCTIVE_CONTROL"
    if evidence["download"] or (evidence["form"] and evidence["type"] in {"submit", "reset"}):
        return "EXTERNAL", "FORM_SUBMISSION_OR_DOWNLOAD"
    if re.search(r"submit\s*\(|requestSubmit\s*\(", evidence["handler"]):
        return "EXTERNAL", "SUBMIT_HANDLER"
    href = evidence["href"]
    if href.lower().replace(" ", "") in {"javascript:;", "javascript:void(0)", "javascript:void(0);"}:
        href = ""
    if href:
        parsed = urlsplit(href)
        if parsed.scheme not in {"http", "https"} or origin(href) != origin(session.config["url"]):
            return "UNKNOWN", "NONLOCAL_OR_SCRIPT_LINK"
        if EXTERNAL.search(parsed.path + " " + parsed.query + " " + parsed.fragment):
            return "UNKNOWN", "POSSIBLE_ACTION_URL"
    if request.target.by == "role" and request.target.role == "button" and request.target.name in session.config.get("buttons", []):
        return "NAVIGATE", "CONFIGURED_CONTROL"
    interactive = evidence["tag"] in {"BUTTON", "A"} or evidence["role"] in {"tab", "button", "link", "menuitem"} or evidence["cursor"] == "pointer"
    if session.user_authorized and interactive and not evidence["form"] and NAVIGATION.fullmatch(label):
        return "NAVIGATE", "NAVIGATION_CONTROL_WITH_DOM_EVIDENCE"
    return "UNKNOWN", "AMBIGUOUS_CONTROL_REQUIRES_APPROVAL"


async def approval_context(element):
    # Prefer the actual form/dialog. Fallback only ignores live status decoration;
    # unfamiliar layouts retain a conservative text binding rather than guessing.
    return await element.evaluate("""el => {
        const control = el.closest('button,a,input,[role="button"],[role="link"]') || el;
        const region = control.form || control.closest('form,[role="dialog"]') || document.body;
        const clone = region.cloneNode(true);
        clone.querySelectorAll('script,style').forEach(n => n.remove());
        if (region === document.body) {
            clone.querySelectorAll('[role="status"],[role="log"],time').forEach(n => n.remove());
        }
        return {target: {tag: control.tagName, text: control.innerText, label: control.getAttribute('aria-label'),
                         href: control.getAttribute('href'), type: control.type, formAction: control.formAction,
                         formMethod: control.formMethod, handler: control.getAttribute('onclick')},
                region: {tag: region.tagName, action: region.getAttribute('action'), method: region.getAttribute('method'),
                         text: clone.textContent}};
    }""")
