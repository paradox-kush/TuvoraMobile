package com.nuvio.app.features.plugins.runtime.js

internal object JsBindings {
    fun buildPolyfillCode(scraperId: String, settingsJson: String): String {
        return """
            globalThis.SCRAPER_ID = "$scraperId";
            globalThis.SCRAPER_SETTINGS = $settingsJson;
            if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
            if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
            if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;

            ${fetchPolyfill()}
            ${abortControllerPolyfill()}
            ${base64Polyfill()}
            ${urlPolyfill()}
            ${cryptoPolyfill()}
            ${cheerioPolyfill()}
            ${requirePolyfill()}
            ${arrayPolyfill()}
            ${objectPolyfill()}
            ${stringPolyfill()}
        """.trimIndent()
    }

    private fun fetchPolyfill() = """
        var fetch = async function(url, options) {
            options = options || {};
            var method = (options.method || 'GET').toUpperCase();
            var headers = options.headers || {};
            var body = options.body || '';
            var followRedirects = options.redirect !== 'manual';
            var result = __native_fetch(url, method, JSON.stringify(headers), body, followRedirects);
            var parsed = JSON.parse(result);
            return {
                ok: parsed.ok,
                status: parsed.status,
                statusText: parsed.statusText,
                url: parsed.url,
                headers: {
                    get: function(name) {
                        return parsed.headers[name.toLowerCase()] || null;
                    }
                },
                text: function() { return Promise.resolve(parsed.body); },
                json: function() {
                    try {
                        if (parsed.body === null || parsed.body === undefined || parsed.body === '') {
                            return Promise.resolve(null);
                        }
                        return Promise.resolve(JSON.parse(parsed.body));
                    } catch (e) {
                        return Promise.resolve(null);
                    }
                }
            };
        };
    """.trimIndent()

    private fun abortControllerPolyfill() = """
        if (typeof AbortSignal === 'undefined') {
            var AbortSignal = function() { this.aborted = false; this.reason = undefined; this._listeners = []; };
            AbortSignal.prototype.addEventListener = function(type, listener) {
                if (type !== 'abort' || typeof listener !== 'function') return;
                this._listeners.push(listener);
            };
            AbortSignal.prototype.removeEventListener = function(type, listener) {
                if (type !== 'abort') return;
                this._listeners = this._listeners.filter(function(l) { return l !== listener; });
            };
            AbortSignal.prototype.dispatchEvent = function(event) {
                if (!event || event.type !== 'abort') return true;
                for (var i = 0; i < this._listeners.length; i++) {
                    try { this._listeners[i].call(this, event); } catch (e) {}
                }
                return true;
            };
            globalThis.AbortSignal = AbortSignal;
        }

        if (typeof AbortController === 'undefined') {
            var AbortController = function() { this.signal = new AbortSignal(); };
            AbortController.prototype.abort = function(reason) {
                if (this.signal.aborted) return;
                this.signal.aborted = true;
                this.signal.reason = reason;
                this.signal.dispatchEvent({ type: 'abort' });
            };
            globalThis.AbortController = AbortController;
        }
    """.trimIndent()

    private fun base64Polyfill() = """
        if (typeof atob === 'undefined') {
            globalThis.atob = function(input) {
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                var str = String(input).replace(/=+$/, '');
                if (str.length % 4 === 1) throw new Error('InvalidCharacterError');
                var output = '';
                var bc = 0, bs, buffer, idx = 0;
                while ((buffer = str.charAt(idx++))) {
                    buffer = chars.indexOf(buffer);
                    if (buffer === -1) continue;
                    bs = bc % 4 ? bs * 64 + buffer : buffer;
                    if (bc++ % 4) output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
                }
                return output;
            };
        }

        if (typeof btoa === 'undefined') {
            globalThis.btoa = function(input) {
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                var str = String(input);
                var output = '';
                for (var block, charCode, idx = 0, map = chars;
                     str.charAt(idx | 0) || (map = '=', idx % 1);
                     output += map.charAt(63 & (block >> (8 - (idx % 1) * 8)))) {
                    charCode = str.charCodeAt(idx += 3 / 4);
                    if (charCode > 0xFF) throw new Error('InvalidCharacterError');
                    block = (block << 8) | charCode;
                }
                return output;
            };
        }
    """.trimIndent()

    private fun urlPolyfill() = """
        var __native_parse_url = typeof __parse_url !== 'undefined' ? __parse_url : function(u) { return JSON.stringify({ protocol: '', host: '', hostname: '', port: '', pathname: '/', search: '', hash: '' }); };
        var URL = function(urlString, base) {
            var fullUrl = urlString;
            if (base && !/^https?:\/\//i.test(urlString)) {
                var b = typeof base === 'string' ? base : base.href;
                if (urlString.charAt(0) === '/') {
                    var m = b.match(/^(https?:\/\/[^\/]+)/);
                    fullUrl = m ? m[1] + urlString : urlString;
                } else {
                    fullUrl = b.replace(/\/[^\/]*$/, '/') + urlString;
                }
            }
            var parsed = __native_parse_url(fullUrl);
            var data = JSON.parse(parsed);
            this.href = fullUrl;
            this.protocol = data.protocol;
            this.host = data.host;
            this.hostname = data.hostname;
            this.port = data.port;
            this.pathname = data.pathname;
            this.search = data.search;
            this.hash = data.hash;
            this.origin = data.protocol + '//' + data.host;
            this.searchParams = new URLSearchParams(data.search || '');
        };
        URL.prototype.toString = function() { return this.href; };

        var URLSearchParams = function(init) {
            this._params = {};
            var self = this;
            if (init && typeof init === 'object' && !Array.isArray(init)) {
                Object.keys(init).forEach(function(key) { self._params[key] = String(init[key]); });
            } else if (typeof init === 'string') {
                init.replace(/^\?/, '').split('&').forEach(function(pair) {
                    var parts = pair.split('=');
                    if (parts[0]) self._params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1] || '');
                });
            }
        };
        URLSearchParams.prototype.toString = function() {
            var self = this;
            return Object.keys(this._params).map(function(key) {
                return encodeURIComponent(key) + '=' + encodeURIComponent(self._params[key]);
            }).join('&');
        };
        URLSearchParams.prototype.get = function(key) { return this._params.hasOwnProperty(key) ? this._params[key] : null; };
        URLSearchParams.prototype.set = function(key, value) { this._params[key] = String(value); };
        URLSearchParams.prototype.append = function(key, value) { this._params[key] = String(value); };
        URLSearchParams.prototype.has = function(key) { return this._params.hasOwnProperty(key); };
        URLSearchParams.prototype.delete = function(key) { delete this._params[key]; };
        URLSearchParams.prototype.keys = function() { return Object.keys(this._params); };
        URLSearchParams.prototype.values = function() {
            var self = this;
            return Object.keys(this._params).map(function(k) { return self._params[k]; });
        };
        URLSearchParams.prototype.entries = function() {
            var self = this;
            return Object.keys(this._params).map(function(k) { return [k, self._params[k]]; });
        };
        URLSearchParams.prototype.forEach = function(callback) {
            var self = this;
            Object.keys(this._params).forEach(function(key) { callback(self._params[key], key, self); });
        };
        URLSearchParams.prototype.getAll = function(key) {
            return this._params.hasOwnProperty(key) ? [this._params[key]] : [];
        };
        URLSearchParams.prototype.sort = function() {
            var sorted = {};
            var self = this;
            Object.keys(this._params).sort().forEach(function(k) { sorted[k] = self._params[k]; });
            this._params = sorted;
        };
    """.trimIndent()

    private fun cryptoPolyfill() = """
        function __hexToWords(hex) {
            var words = [];
            for (var i = 0; i < hex.length; i += 8) {
                var chunk = hex.substring(i, i + 8);
                while (chunk.length < 8) chunk += '0';
                words.push(parseInt(chunk, 16) | 0);
            }
            return words;
        }

        function __wordsToHex(words, sigBytes) {
            var hex = '';
            for (var i = 0; i < sigBytes; i++) {
                var word = words[i >>> 2] || 0;
                var byte = (word >>> (24 - (i % 4) * 8)) & 0xff;
                var part = byte.toString(16);
                if (part.length < 2) part = '0' + part;
                hex += part;
            }
            return hex;
        }

        function __wordArrayToHex(value) {
            if (!value) return '';
            if (typeof value.__hex === 'string') return value.__hex.toLowerCase();
            if (Array.isArray(value.words) && typeof value.sigBytes === 'number') {
                return __wordsToHex(value.words, value.sigBytes);
            }
            return typeof __crypto_utf8_to_hex !== 'undefined' ? __crypto_utf8_to_hex(String(value)) : '';
        }

        function __buildWordArray(hex, utf8Override) {
            var normalizedHex = (hex || '').toLowerCase();
            if (normalizedHex.length % 2 !== 0) normalizedHex = '0' + normalizedHex;
            var wordArray = {
                __hex: normalizedHex,
                __utf8: utf8Override !== undefined ? utf8Override : (typeof __crypto_hex_to_utf8 !== 'undefined' ? __crypto_hex_to_utf8(normalizedHex) : ''),
                sigBytes: normalizedHex.length / 2,
                words: __hexToWords(normalizedHex),
                toString: function(encoder) {
                    if (!encoder || encoder === CryptoJS.enc.Hex) return this.__hex;
                    if (encoder === CryptoJS.enc.Utf8) return this.__utf8;
                    if (encoder === CryptoJS.enc.Base64) return typeof __crypto_base64_encode !== 'undefined' ? __crypto_base64_encode(this.__utf8) : '';
                    return this.__hex;
                },
                clamp: function() { return this; },
                concat: function(other) {
                    var otherHex = __wordArrayToHex(other);
                    this.__hex += otherHex;
                    this.__utf8 = typeof __crypto_hex_to_utf8 !== 'undefined' ? __crypto_hex_to_utf8(this.__hex) : '';
                    this.sigBytes = this.__hex.length / 2;
                    this.words = __hexToWords(this.__hex);
                    return this;
                }
            };
            return wordArray;
        }

        function __wordArrayFromHex(hex) { return __buildWordArray(hex, undefined); }
        function __wordArrayFromUtf8(text) {
            var utf8 = text == null ? '' : String(text);
            var hex = typeof __crypto_utf8_to_hex !== 'undefined' ? __crypto_utf8_to_hex(utf8) : '';
            return __buildWordArray(hex, utf8);
        }
        function __wordArrayFromBase64(base64) {
            var utf8 = typeof __crypto_base64_decode !== 'undefined' ? __crypto_base64_decode(base64 || '') : '';
            return __wordArrayFromUtf8(utf8);
        }

        function __normalizeWordArrayInput(value) {
            if (value && typeof value === 'object' && typeof value.__utf8 === 'string') return value.__utf8;
            if (value && typeof value === 'object' && typeof value.__hex === 'string') return typeof __crypto_hex_to_utf8 !== 'undefined' ? __crypto_hex_to_utf8(value.__hex) : '';
            if (value && typeof value === 'object' && Array.isArray(value.words) && typeof value.sigBytes === 'number') {
                return typeof __crypto_hex_to_utf8 !== 'undefined' ? __crypto_hex_to_utf8(__wordsToHex(value.words, value.sigBytes)) : '';
            }
            if (value == null) return '';
            return String(value);
        }

        function __bufferToUint8(data) {
            if (data instanceof Uint8Array) return data;
            if (data instanceof ArrayBuffer) return new Uint8Array(data);
            if (typeof data === 'string') return new TextEncoder().encode(data);
            return new Uint8Array(0);
        }

        var CryptoJS = {
            enc: {
                Hex: { stringify: function(wa) { return __wordArrayToHex(wa); }, parse: function(s) { return __wordArrayFromHex(s); } },
                Utf8: { stringify: function(wa) { return wa.toString(CryptoJS.enc.Utf8); }, parse: function(s) { return __wordArrayFromUtf8(s); } },
                Base64: { stringify: function(wa) { return wa.toString(CryptoJS.enc.Base64); }, parse: function(s) { return __wordArrayFromBase64(s); } }
            },
            lib: { WordArray: { create: function(words, sigBytes) { return __buildWordArray(__wordsToHex(words, sigBytes), undefined); } } },
            mode: { CBC: 'AES-CBC', GCM: 'AES-GCM', ECB: 'AES-ECB' },
            pad: { Pkcs7: 'Pkcs7', NoPadding: 'NoPadding' },
            algo: { SHA256: 'SHA256' },
            MD5: function(m) { return __wordArrayFromHex(__crypto_digest_hex('MD5', __normalizeWordArrayInput(m))); },
            SHA1: function(m) { return __wordArrayFromHex(__crypto_digest_hex('SHA1', __normalizeWordArrayInput(m))); },
            SHA256: function(m) { return __wordArrayFromHex(__crypto_digest_hex('SHA256', __normalizeWordArrayInput(m))); },
            SHA512: function(m) { return __wordArrayFromHex(__crypto_digest_hex('SHA512', __normalizeWordArrayInput(m))); },
            PBKDF2: function(pass, salt, options) {
                var pBytes = __bufferToUint8(__normalizeWordArrayInput(pass));
                var sBytes = __bufferToUint8(__normalizeWordArrayInput(salt));
                var iter = options.iterations || 1000;
                var kSize = options.keySize || (256/32);
                var algo = options.hasher === CryptoJS.algo.SHA256 ? 'SHA256' : 'SHA1';
                var resBytes = typeof __crypto_pbkdf2_raw !== 'undefined' ? __crypto_pbkdf2_raw(pBytes, sBytes, iter, kSize * 32, algo) : new Uint8Array(0);
                return __wordArrayFromHex(__wordsToHex(Array.from(resBytes), resBytes.length));
            },
            AES: {
                encrypt: function(message, key, options) {
                    var data = __bufferToUint8(__normalizeWordArrayInput(message));
                    var kBytes = __bufferToUint8(__wordArrayToHex(key));
                    var ivBytes = __bufferToUint8(__wordArrayToHex(options.iv || ''));
                    var mode = options.mode || 'AES-CBC';
                    var resBytes = typeof __crypto_aes_encrypt_raw !== 'undefined' ? __crypto_aes_encrypt_raw(mode, kBytes, ivBytes, data) : new Uint8Array(0);
                    var wa = __wordArrayFromHex(__wordsToHex(Array.from(resBytes), resBytes.length));
                    return {
                        ciphertext: wa,
                        toString: function() { return wa.toString(CryptoJS.enc.Base64); }
                    };
                },
                decrypt: function(cipher, key, options) {
                    var data = typeof cipher === 'string' ? __bufferToUint8(typeof __crypto_base64_decode !== 'undefined' ? __crypto_base64_decode(cipher) : '') : (cipher.ciphertext ? __bufferToUint8(typeof __crypto_base64_decode !== 'undefined' ? __crypto_base64_decode(cipher.ciphertext.toString(CryptoJS.enc.Base64)) : '') : __bufferToUint8(cipher));
                    var kBytes = __bufferToUint8(__wordArrayToHex(key));
                    var ivBytes = __bufferToUint8(__wordArrayToHex(options.iv || ''));
                    var mode = options.mode || 'AES-CBC';
                    var resBytes = typeof __crypto_aes_decrypt_raw !== 'undefined' ? __crypto_aes_decrypt_raw(mode, kBytes, ivBytes, data) : new Uint8Array(0);
                    var plain = new TextDecoder().decode(resBytes);
                    return { toString: function(enc) { return plain; } };
                }
            }
        };
        globalThis.CryptoJS = CryptoJS;

        globalThis.crypto = {
            subtle: {
                digest: async function(algo, data) {
                    var bytes = __bufferToUint8(data);
                    var res = typeof __crypto_digest_raw !== 'undefined' ? __crypto_digest_raw(algo.name || algo, bytes) : new Uint8Array(0);
                    return res.buffer;
                },
                importKey: async function(fmt, data, algo, ext, use) { return { _raw: data, _algo: algo }; },
                deriveBits: async function(params, key, len) {
                    var pBytes = __bufferToUint8(key._raw);
                    var sBytes = __bufferToUint8(params.salt);
                    var res = typeof __crypto_pbkdf2_raw !== 'undefined' ? __crypto_pbkdf2_raw(pBytes, sBytes, params.iterations, len, params.hash) : new Uint8Array(0);
                    return res.buffer;
                },
                encrypt: async function(params, key, data) {
                    var kBytes = __bufferToUint8(key._raw);
                    var ivBytes = __bufferToUint8(params.iv || '');
                    var dBytes = __bufferToUint8(data);
                    var res = typeof __crypto_aes_encrypt_raw !== 'undefined' ? __crypto_aes_encrypt_raw(params.name, kBytes, ivBytes, dBytes) : new Uint8Array(0);
                    return res.buffer;
                },
                decrypt: async function(params, key, data) {
                    var kBytes = __bufferToUint8(key._raw);
                    var ivBytes = __bufferToUint8(params.iv || '');
                    var dBytes = __bufferToUint8(data);
                    var res = typeof __crypto_aes_decrypt_raw !== 'undefined' ? __crypto_aes_decrypt_raw(params.name, kBytes, ivBytes, dBytes) : new Uint8Array(0);
                    return res.buffer;
                },
                sign: async function(algo, key, data) {
                    var algoName = typeof algo === 'string' ? algo : (algo.name || '');
                    var kBytes = __bufferToUint8(key._raw);
                    var dBytes = __bufferToUint8(data);
                    var res = typeof __crypto_sign_raw !== 'undefined' ? __crypto_sign_raw(algoName, kBytes, dBytes) : new Uint8Array(0);
                    return res.buffer;
                },
                verify: async function(algo, key, sig, data) {
                    var algoName = typeof algo === 'string' ? algo : (algo.name || '');
                    var kBytes = __bufferToUint8(key._raw);
                    var sBytes = __bufferToUint8(sig);
                    var dBytes = __bufferToUint8(data);
                    return typeof __crypto_verify_raw !== 'undefined' ? __crypto_verify_raw(algoName, kBytes, sBytes, dBytes) : false;
                }
            },
            getRandomValues: function(arr) {

                var bytes = typeof __crypto_get_random_values !== 'undefined' ? __crypto_get_random_values(arr.length) : new Uint8Array(arr.length);
                for (var i = 0; i < arr.length; i++) arr[i] = bytes[i];
                return arr;
            }
        };

        // WebAssembly placeholder
        globalThis.WebAssembly = {
            instantiate: async function(bufferSource, importObject) {
                console.warn("WebAssembly.instantiate called (placeholder)");
                return { instance: { exports: {} }, module: {} };
            }
        };
    """.trimIndent()

    private fun cheerioPolyfill() = """
        var cheerio = {
            load: function(html) {
                var docId = __cheerio_load(html);
                var $ = function(selector, context) {
                    if (selector && selector._elementIds) return selector;
                    if (context && context._elementIds && context._elementIds.length > 0) {
                        var allIds = [];
                        for (var i = 0; i < context._elementIds.length; i++) {
                            var childIdsJson = __cheerio_find(docId, context._elementIds[i], selector);
                            var childIds = JSON.parse(childIdsJson);
                            allIds = allIds.concat(childIds);
                        }
                        return createCheerioWrapperFromIds(docId, allIds);
                    }
                    return createCheerioWrapper(docId, selector);
                };
                $.html = function(el) {
                    if (el && el._elementIds && el._elementIds.length > 0) {
                        return __cheerio_html(docId, el._elementIds[0]);
                    }
                    return __cheerio_html(docId, '');
                };
                return $;
            }
        };

        function createCheerioWrapper(docId, selector) {
            var elementIds;
            if (typeof selector === 'string') {
                var idsJson = __cheerio_select(docId, selector);
                elementIds = JSON.parse(idsJson);
            } else {
                elementIds = [];
            }
            return createCheerioWrapperFromIds(docId, elementIds);
        }

        function createCheerioWrapperFromIds(docId, ids) {
            var wrapper = {
                _docId: docId,
                _elementIds: ids,
                length: ids.length,
                each: function(callback) {
                    for (var i = 0; i < ids.length; i++) {
                        var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                        callback.call(elWrapper, i, elWrapper);
                    }
                    return wrapper;
                },
                find: function(sel) {
                    var allIds = [];
                    for (var i = 0; i < ids.length; i++) {
                        var childIdsJson = __cheerio_find(docId, ids[i], sel);
                        var childIds = JSON.parse(childIdsJson);
                        allIds = allIds.concat(childIds);
                    }
                    return createCheerioWrapperFromIds(docId, allIds);
                },
                text: function() {
                    if (ids.length === 0) return '';
                    return __cheerio_text(docId, ids.join(','));
                },
                html: function() {
                    if (ids.length === 0) return '';
                    return __cheerio_inner_html(docId, ids[0]);
                },
                attr: function(name) {
                    if (ids.length === 0) return undefined;
                    var val = __cheerio_attr(docId, ids[0], name);
                    return val === '__UNDEFINED__' ? undefined : val;
                },
                first: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[0]] : []); },
                last: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[ids.length - 1]] : []); },
                next: function() {
                    var nextIds = [];
                    for (var i = 0; i < ids.length; i++) {
                        var nextId = __cheerio_next(docId, ids[i]);
                        if (nextId && nextId !== '__NONE__') nextIds.push(nextId);
                    }
                    return createCheerioWrapperFromIds(docId, nextIds);
                },
                prev: function() {
                    var prevIds = [];
                    for (var i = 0; i < ids.length; i++) {
                        var prevId = __cheerio_prev(docId, ids[i]);
                        if (prevId && prevId !== '__NONE__') prevIds.push(prevId);
                    }
                    return createCheerioWrapperFromIds(docId, prevIds);
                },
                eq: function(index) {
                    if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
                    return createCheerioWrapperFromIds(docId, []);
                },
                get: function(index) {
                    if (typeof index === 'number') {
                        if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
                        return undefined;
                    }
                    return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); });
                },
                map: function(callback) {
                    var results = [];
                    for (var i = 0; i < ids.length; i++) {
                        var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                        var result = callback.call(elWrapper, i, elWrapper);
                        if (result !== undefined && result !== null) results.push(result);
                    }
                    return {
                        length: results.length,
                        get: function(index) { return typeof index === 'number' ? results[index] : results; },
                        toArray: function() { return results; }
                    };
                },
                filter: function(selectorOrCallback) {
                    if (typeof selectorOrCallback === 'function') {
                        var filteredIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            var result = selectorOrCallback.call(elWrapper, i, elWrapper);
                            if (result) filteredIds.push(ids[i]);
                        }
                        return createCheerioWrapperFromIds(docId, filteredIds);
                    }
                    return wrapper;
                },
                children: function(sel) { return this.find(sel || '*'); },
                parent: function() { return createCheerioWrapperFromIds(docId, []); },
                toArray: function() { return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); }); }
            };
            return wrapper;
        }
    """.trimIndent()

    private fun requirePolyfill() = """
        var require = function(moduleName) {
            if (moduleName === 'cheerio' || moduleName === 'cheerio-without-node-native' || moduleName === 'react-native-cheerio') {
                return cheerio;
            }
            if (moduleName === 'crypto-js') {
                return CryptoJS;
            }
            throw new Error("Module '" + moduleName + "' is not available");
        };
    """.trimIndent()

    private fun arrayPolyfill() = """
        if (!Array.prototype.flat) {
            Array.prototype.flat = function(depth) {
                depth = depth === undefined ? 1 : Math.floor(depth);
                if (depth < 1) return Array.prototype.slice.call(this);
                return (function flatten(arr, d) {
                    return d > 0
                        ? arr.reduce(function(acc, val) { return acc.concat(Array.isArray(val) ? flatten(val, d - 1) : val); }, [])
                        : arr.slice();
                })(this, depth);
            };
        }

        if (!Array.prototype.flatMap) {
            Array.prototype.flatMap = function(callback, thisArg) { return this.map(callback, thisArg).flat(); };
        }
    """.trimIndent()

    private fun objectPolyfill() = """
        if (!Object.entries) {
            Object.entries = function(obj) {
                var result = [];
                for (var key in obj) {
                    if (obj.hasOwnProperty(key)) result.push([key, obj[key]]);
                }
                return result;
            };
        }

        if (!Object.fromEntries) {
            Object.fromEntries = function(entries) {
                var result = {};
                for (var i = 0; i < entries.length; i++) {
                    result[entries[i][0]] = entries[i][1];
                }
                return result;
            };
        }
    """.trimIndent()

    private fun stringPolyfill() = """
        if (!String.prototype.replaceAll) {
            String.prototype.replaceAll = function(search, replace) {
                if (search instanceof RegExp) {
                    if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
                    return this.replace(search, replace);
                }
                return this.split(search).join(replace);
            };
        }
    """.trimIndent()
}
