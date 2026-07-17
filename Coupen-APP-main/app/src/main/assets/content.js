// CoupenApp Content Script v6 - Injected via evaluateJavascript()
(function () {
    if (window.__coupenAppInjected) return;
    window.__coupenAppInjected = true;

    function processHash() {
        var hash = window.location.hash.substring(1);
        if (!hash) return;
        var params = new URLSearchParams(hash);
        var giftCode = params.get('gc');
        var fireAction = params.get('fire');
        if (giftCode || fireAction === 'true') {
            startPolling(giftCode, fireAction);
        }
    }

    processHash();

    window.addEventListener('hashchange', function () {
        processHash();
    });

    window.addEventListener('CoupenCommand', function (e) {
        if (e.detail) {
            startPolling(e.detail.gc, String(e.detail.fire));
        }
    });

    var urlParams = new URLSearchParams(window.location.search);
    if (urlParams.has('gc') || urlParams.has('fire')) {
        startPolling(urlParams.get('gc'), urlParams.get('fire'));
    }
})();

function startPolling(giftCode, fireAction) {
    var attempts = 0;
    var maxAttempts = 300;
    var timer = null;

    function attempt() {
        attempts++;
        if (giftCode) {
            var input = findGiftCodeInput();
            if (input) {
                if (timer) cancelAnimationFrame(timer);
                applyCode(input, giftCode);
                if (fireAction === 'true') {
                    // Give the site one frame to register the value, then redeem.
                    requestAnimationFrame(clickRedeemButton);
                }
                return;
            }
        } else if (fireAction === 'true') {
            if (timer) cancelAnimationFrame(timer);
            clickRedeemButton();
            return;
        }
        if (attempts >= maxAttempts) {
            if (timer) cancelAnimationFrame(timer);
            return;
        }
        timer = requestAnimationFrame(attempt);
    }
    attempt();
}

// Cached DOM references so repeated Apply/Fire don't re-scan the page.
var cachedInput = null;
var cachedButton = null;

function findGiftCodeInput() {
    // Reuse the last input if it's still on the page and visible — O(1) fast path.
    if (cachedInput && document.contains(cachedInput) && isVisible(cachedInput)) {
        return cachedInput;
    }
    cachedInput = null;

    var selectors = [
        'input[placeholder*="enter gift code" i]',
        'input[placeholder*="gift code" i]',
        'input[placeholder*="redeem" i]',
        'input[placeholder*="coupon" i]',
        'input#giftCode',
        'input[name*="gift" i]',
        'input[name*="code" i]',
        'input[name*="redeem" i]'
    ];
    for (var i = 0; i < selectors.length; i++) {
        var el = document.querySelector(selectors[i]);
        if (el && isVisible(el)) { cachedInput = el; return el; }
    }
    var inputs = document.querySelectorAll('input[type="text"], input:not([type])');
    for (var j = 0; j < inputs.length; j++) {
        var inp = inputs[j];
        if (!isVisible(inp)) continue;
        var ph = (inp.placeholder || '').toLowerCase();
        var nm = (inp.name || '').toLowerCase();
        var id = (inp.id || '').toLowerCase();
        if (ph.includes('gift') || ph.includes('code') || ph.includes('redeem') ||
            nm.includes('gift') || nm.includes('code') || id.includes('gift') || id.includes('code')) {
            cachedInput = inp; return inp;
        }
    }
    var visibleInputs = [];
    for (var k = 0; k < inputs.length; k++) {
        if (isVisible(inputs[k])) visibleInputs.push(inputs[k]);
    }
    if (visibleInputs.length === 1) { cachedInput = visibleInputs[0]; return visibleInputs[0]; }
    return null;
}

function applyCode(input, code) {
    input.focus();
    input.click();
    input.value = '';
    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    if (setter) {
        setter.call(input, code);
    } else {
        input.value = code;
    }
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    input.style.border = '3px solid #00FF00';
    input.style.backgroundColor = '#E0FFE0';
}

function clickRedeemButton() {
    // Reuse the last redeem button if it's still on the page — O(1) fast path.
    if (cachedButton && document.contains(cachedButton)) {
        fireClick(cachedButton);
        return;
    }
    cachedButton = null;

    var buttons = document.querySelectorAll('button, a, input[type="button"], input[type="submit"], div[role="button"]');
    for (var i = 0; i < buttons.length; i++) {
        var btn = buttons[i];
        var text = (btn.innerText || btn.textContent || btn.value || '').trim().toLowerCase();
        if (/^(receive|redeem|claim|confirm|apply|submit)$/i.test(text)) {
            cachedButton = btn;
            fireClick(btn);
            return;
        }
    }
}

function fireClick(btn) {
    btn.disabled = false;
    btn.removeAttribute('disabled');
    btn.style.pointerEvents = 'auto';
    btn.click();
}

function isVisible(el) {
    if (!el) return false;
    return el.offsetWidth > 0 && el.offsetHeight > 0;
}
