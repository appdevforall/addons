package org.appdevforall.localstore.server

import java.util.Base64

/**
 * The store's look, from the Eight-Armed Merchant icon: its awning across the top, a cream
 * ground, and outlined "sticker" cards and buttons. Shared by every page the server itself sends
 * buyers — the PIN page and the short message pages. The catalog page's template uses the same
 * palette. No web fonts: stores run offline.
 */
object Brand {

    /** The icon, animated, as a self-contained image. Kept in an <img>, so its styles can't touch the page. */
    const val OCTOPUS_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><style>.l{animation:l 3s ease-in-out infinite;transform-origin:36px 64px}.r{animation:r 3s ease-in-out 1.5s infinite;transform-origin:72px 64px}.e{animation:e 4s infinite;transform-origin:54px 52px}.p{animation:p 6s ease-in-out infinite}.b{animation:b 1.6s ease-in-out infinite alternate}.c{animation-delay:.8s}@keyframes l{50%{transform:rotate(-12deg)}}@keyframes r{50%{transform:rotate(12deg)}}@keyframes e{0%,90%,to{transform:none}95%{transform:scaleY(.1)}}@keyframes p{30%{transform:translate(2px,-1px)}60%{transform:translate(-2px,1px)}}@keyframes b{to{transform:translateY(-2px)}}@media(prefers-reduced-motion:reduce){*{animation:none!important}}</style><defs><pattern id="a" width="24" height="30" patternUnits="userSpaceOnUse"><path fill="#FF4F3A" d="M0 0h12v24H0z"/><path fill="#FFF3D6" d="M12 0h12v24H12z"/><circle cx="6" cy="24" r="6" fill="#FF4F3A"/><circle cx="18" cy="24" r="6" fill="#FFF3D6"/></pattern></defs><path fill="#0B6E6E" d="M0 0h108v108H0z"/><path fill="url(#a)" d="M0 0h108v30H0z"/><g stroke="#2B1B3D" stroke-width="2"><g class="l"><path d="M36 64q-12-4-14-14" fill="none" stroke="#8E5BFF" stroke-width="6" stroke-linecap="round"/><rect x="17" y="44" width="10" height="10" rx="2.5" fill="#FFE14D" transform="rotate(-14 22 49)"/></g><g class="r"><path d="M72 64q12-4 14-14" fill="none" stroke="#8E5BFF" stroke-width="6" stroke-linecap="round"/><rect x="81" y="44" width="10" height="10" rx="2.5" fill="#4DD8FF" transform="rotate(12 86 49)"/></g><ellipse cx="54" cy="54" rx="22" ry="20" fill="#8E5BFF" stroke-width="3"/><g class="e"><circle cx="46" cy="52" r="6.5" fill="#fff"/><circle cx="62" cy="52" r="6.5" fill="#fff"/><g class="p" fill="#2B1B3D" stroke="none"><circle cx="48" cy="50" r="3"/><circle cx="60" cy="54" r="3"/></g></g><path d="M50 63q4 3 8 0" fill="none" stroke-width="2.5" stroke-linecap="round"/><rect x="12" y="70" width="84" height="24" rx="4" fill="#FF9E2C" stroke-width="3"/><path d="M14 79h80" stroke-width="1.5" opacity=".4"/><path d="M40 70q-2-6-6-4m34 4q2-6 6-4" fill="none" stroke="#8E5BFF" stroke-width="6" stroke-linecap="round"/><rect class="b" x="26" y="60" width="10" height="10" rx="2.5" fill="#7CFF6B"/><rect class="b c" x="72" y="60" width="10" height="10" rx="2.5" fill="#FF6FD8"/></g><circle cx="30" cy="84" r="1.8" fill="#2B1B3D" opacity=".35"/><circle cx="78" cy="84" r="1.8" fill="#2B1B3D" opacity=".35"/></svg>"""

    val octopusDataUri: String = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(OCTOPUS_SVG.toByteArray(Charsets.UTF_8))

    const val INK = "#2B1B3D"

    private const val CSS =
        "*{box-sizing:border-box;margin:0}" +
        "body{background:#FFF3D6;color:#2B1B3D;font-family:ui-rounded,\"SF Pro Rounded\",system-ui,sans-serif}" +
        // The awning: red and cream stripes with scalloped edges, over the teal of the icon.
        ".aw{height:26px;background:radial-gradient(circle at 12px 14px,#FF4F3A 11.5px,transparent 12px) 0 0/48px 26px repeat-x," +
        "radial-gradient(circle at 36px 14px,#FFF3D6 11.5px,transparent 12px) 0 0/48px 26px repeat-x," +
        "repeating-linear-gradient(90deg,#FF4F3A 0 24px,#FFF3D6 24px 48px) 0 0/100% 14px no-repeat,#0B6E6E}" +
        "main{max-width:22em;margin:0 auto;padding:28px 16px}" +
        ".k{background:#fff;border:3px solid #2B1B3D;border-radius:16px;box-shadow:5px 5px 0 #2B1B3D;padding:22px 20px;text-align:center}" +
        ".k img{width:96px;height:96px;border-radius:22px;border:3px solid #2B1B3D;display:block;margin:0 auto 14px}" +
        "h1{font-size:22px;font-weight:800;margin-bottom:14px;overflow-wrap:anywhere}" +
        "p{font-size:17px;line-height:1.45}" +
        "label{display:block;font-weight:700;text-align:left}" +
        "input{font:inherit;font-size:1.4em;width:100%;padding:.45em .6em;margin:.4em 0 .9em;border:3px solid #2B1B3D;" +
        "border-radius:12px;letter-spacing:.3em;text-align:center;background:#fff;color:#2B1B3D}" +
        "input:focus,button:focus-visible{outline:3px solid #8E5BFF;outline-offset:2px}" +
        "button{font:inherit;font-size:1.15em;font-weight:800;width:100%;padding:.6em;background:#FF9E2C;color:#2B1B3D;" +
        "border:3px solid #2B1B3D;border-radius:12px;box-shadow:3px 3px 0 #2B1B3D;cursor:pointer}" +
        "button:active{transform:translate(3px,3px);box-shadow:none}" +
        ".er{color:#C2261A;font-weight:700;margin-bottom:12px}"

    /** A complete page: the awning, then a card holding the octopus and [body]. [title] must already be escaped. */
    fun page(title: String, body: String): String =
        "<!doctype html><html lang=en><head><meta charset=utf-8>" +
        "<meta name=viewport content=\"width=device-width,initial-scale=1\"><title>$title</title>" +
        "<style>$CSS</style></head><body><div class=aw></div><main><div class=k>" +
        "<img src=\"$octopusDataUri\" alt=\"\">$body</div></main></body></html>"
}
