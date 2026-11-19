"""Session-bound editor references, independent of website-specific selectors."""
import secrets


async def discover(frame, references):
    result = []
    for ref, (owner_frame, handle) in list(references.items()):
        try:
            attached = not owner_frame.is_detached() and await handle.evaluate('e => e.isConnected')
        except Exception:
            attached = False
        if not attached:
            references.pop(ref, None)
            await handle.dispose()
    candidates = frame.locator('input:not([type=password]):not([type=hidden]):not([type=file]),textarea,[contenteditable],body')
    for handle in await candidates.element_handles():
        info = await handle.evaluate("""e => {
            const rich = e.isContentEditable || (e.tagName === 'BODY' && document.designMode === 'on');
            if (!(rich || ['INPUT','TEXTAREA'].includes(e.tagName)) || !e.getClientRects().length ||
                e.disabled || e.readOnly || (rich && e.parentElement?.isContentEditable && e.tagName !== 'BODY')) return null;
            return {kind: rich ? 'richtext' : 'input', name: e.getAttribute('aria-label') ||
                e.getAttribute('placeholder') || e.getAttribute('data-placeholder') || e.getAttribute('title') || '',
                tag: e.tagName.toLowerCase()};
        }""")
        if not info:
            await handle.dispose()
            continue
        ref = await handle.get_attribute('data-finflow-editor')
        existing = references.get(ref)
        if not existing or existing[0] != frame or not await handle.evaluate('(e, old) => e === old', existing[1]):
            ref = secrets.token_hex(12)
            await handle.evaluate('(e, ref) => e.setAttribute("data-finflow-editor", ref)', ref)
            references[ref] = (frame, handle)
        else:
            await handle.dispose()
        result.append(dict(info, target={"by": "ref", "name": ref}))
    return result


async def resolve(frame, target, references):
    from .engine import locate
    if target.by != 'ref':
        return locate(frame, target)
    entry = references.get(target.name)
    if not entry or entry[0] != frame:
        raise ValueError('EDITOR_REFERENCE_EXPIRED：编辑器编号或所属框架无效，请重新观察页面')
    locator = frame.locator(f'[data-finflow-editor="{target.name}"]')
    if await locator.count() != 1 or not await locator.evaluate('(e, old) => e === old && e.isConnected', entry[1]):
        raise ValueError('EDITOR_REFERENCE_EXPIRED：编辑器已变化，请重新观察页面')
    return locator


async def fill_verified(target, value):
    from playwright.async_api import expect
    info = await target.evaluate("e => ({native:['INPUT','TEXTAREA'].includes(e.tagName), editable:e.isContentEditable, design:e.tagName==='BODY' && document.designMode==='on', type:e.type})")
    if info['type'] in ('password', 'file') or not (info['native'] or info['editable'] or info['design']):
        raise ValueError('EDITOR_NOT_EDITABLE：目标不是可填写编辑器，请重新观察页面')
    if info['design']:
        await target.focus()
        await target.press('ControlOrMeta+A')
        await target.press('Backspace')
        await target.type(value)
    else:
        await target.fill(value)
    if info['native']:
        await expect(target).to_have_value(value)
    else:
        await expect(target).to_have_text(value, use_inner_text=True)
