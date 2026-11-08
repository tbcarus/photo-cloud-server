'use strict';

// Просмотр папки по публичной ссылке. Права проверяет Server на каждом запросе; здесь только отображение.
// Имена из данных пользователя выводятся только через textContent — HTML из них не разбирается.
// Токен ссылки не выводится в консоль и не уходит на другие адреса: все запросы — к тому же Server.
(function () {
    const PAGE_SIZE = 50;
    const VIEWABLE = new Set(['image/jpeg', 'image/png', 'image/webp']);
    const TYPE_LABELS = { IMAGE: 'img', VIDEO: 'video', AUDIO: 'audio', DOCUMENT: 'doc', ARCHIVE: 'zip' };

    // Страница открыта как …/s/{token}; публичные API — …/api/v1/public/s/{token}.
    const token = location.pathname.split('/').pop();
    const api = new URL('../api/v1/public/s/' + encodeURIComponent(token), location.href).pathname;

    const el = {
        crumbs: document.getElementById('crumbs'),
        title: document.getElementById('title'),
        status: document.getElementById('status'),
        content: document.getElementById('content'),
        folders: document.getElementById('folders'),
        files: document.getElementById('files'),
        empty: document.getElementById('empty'),
        more: document.getElementById('more'),
        moreError: document.getElementById('more-error'),
    };

    let root = null;     // корень ссылки {id, name}
    let current = null;  // текущая папка
    let nextPage = 0;
    let generation = 0;  // ответы, пришедшие после ухода из папки, отбрасываются

    class LoadError extends Error {
        constructor(kind) {
            super(kind);
            this.kind = kind; // gone | network | server
        }
    }

    async function getJson(path) {
        let response;
        try {
            response = await fetch(path, { cache: 'no-store', credentials: 'same-origin' });
        } catch (e) {
            throw new LoadError('network');
        }
        if (response.status === 404) {
            throw new LoadError('gone');
        }
        if (!response.ok) {
            throw new LoadError('server');
        }
        return response.json();
    }

    const childrenUrl = (id) => api + '/folders/' + id + '/children';
    const filesUrl = (id, page) => api + '/files?folderId=' + id + '&page=' + page + '&size=' + PAGE_SIZE;
    const fileUrl = (id, action) => api + '/files/' + id + '/' + action;

    // Путь хранится в адресе как #/id/id: работает кнопка «Назад» и обновление страницы.
    function hashOf(chain) {
        return '#/' + chain.slice(1).map((f) => f.id).join('/');
    }

    function idsFromHash() {
        const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean);
        return parts.every((p) => /^[1-9][0-9]{0,18}$/.test(p)) ? parts.map(Number) : [];
    }

    function message(error) {
        if (error.kind === 'gone') {
            return 'Папка недоступна. Возможно, срок действия ссылки истёк, доступ отозван или папка удалена.';
        }
        if (error.kind === 'network') {
            return 'Не удалось связаться с сервером. Проверьте подключение и повторите попытку.';
        }
        return 'Сервер временно не может выполнить запрос. Повторите попытку позже.';
    }

    function node(tag, className, text) {
        const element = document.createElement(tag);
        if (className) {
            element.className = className;
        }
        if (text !== undefined) {
            element.textContent = text;
        }
        return element;
    }

    function formatSize(bytes) {
        const units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
        let value = bytes;
        let unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return value.toLocaleString('ru-RU', { maximumFractionDigits: unit === 0 ? 0 : 1 }) + ' ' + units[unit];
    }

    function typeLabel(file) {
        const dot = file.name.lastIndexOf('.');
        const extension = dot > 0 ? file.name.slice(dot + 1) : '';
        if (/^[A-Za-z0-9]{1,5}$/.test(extension)) {
            return extension;
        }
        return TYPE_LABELS[file.fileType] || 'file';
    }

    function showStatus(text, retry) {
        el.content.hidden = true;
        el.status.replaceChildren(node('span', retry ? 'error' : '', text));
        if (retry) {
            const button = node('button', 'retry', 'Повторить');
            button.type = 'button';
            button.addEventListener('click', render);
            el.status.append(document.createElement('br'), button);
        }
        el.status.hidden = false;
    }

    function showError(error, chain) {
        if (error.kind === 'gone') {
            // Недоступная папка: прежние имя и путь больше не показываются.
            document.title = 'Папка недоступна — PhotoCloud';
            el.title.textContent = '';
            el.crumbs.replaceChildren();
        }
        showStatus(message(error), error.kind !== 'gone');
        if (error.kind === 'gone' && chain && chain.length > 1) {
            const link = node('a', '', 'Вернуться к началу');
            link.href = '#/';
            el.status.append(document.createElement('br'), link);
        }
    }

    function drawCrumbs(chain) {
        el.crumbs.replaceChildren(...chain.map((folder, i) => {
            if (i === chain.length - 1) {
                return node('span', '', folder.name);
            }
            const link = node('a', '', folder.name);
            link.href = hashOf(chain.slice(0, i + 1));
            return link;
        }));
    }

    function folderRow(chain, folder) {
        const link = node('a', 'row');
        link.href = hashOf(chain.concat(folder));
        const info = node('div', 'info');
        info.append(node('div', 'name', folder.name));
        link.append(node('span', 'thumb folder', 'папка'), info);
        const item = document.createElement('li');
        item.append(link);
        return item;
    }

    function fileRow(file) {
        const item = node('li', 'row');
        const viewable = VIEWABLE.has(file.mimeType);
        const thumb = node(viewable ? 'a' : 'span', 'thumb');
        const label = typeLabel(file);
        if (viewable) {
            // Изображение открывается штатным просмотром браузера в новой вкладке.
            thumb.href = fileUrl(file.id, 'view');
            thumb.target = '_blank';
            thumb.rel = 'noopener noreferrer';
            thumb.title = 'Открыть';
            const image = document.createElement('img');
            image.alt = '';
            image.loading = 'lazy';
            image.decoding = 'async';
            image.addEventListener('error', () => thumb.replaceChildren(label), { once: true });
            image.src = fileUrl(file.id, 'thumbnail');
            thumb.append(image);
        } else {
            thumb.textContent = label;
        }

        const info = node('div', 'info');
        if (viewable) {
            const name = node('a', 'name', file.name);
            name.href = fileUrl(file.id, 'view');
            name.target = '_blank';
            name.rel = 'noopener noreferrer';
            info.append(name);
        } else {
            info.append(node('div', 'name', file.name));
        }
        info.append(node('div', 'size', formatSize(file.size)));

        const download = node('a', 'download', 'Скачать');
        download.href = fileUrl(file.id, 'download');
        download.download = '';
        item.append(thumb, info, download);
        return item;
    }

    function appendFiles(page) {
        el.files.append(...page.items.map(fileRow));
        nextPage = page.page + 1;
        el.more.hidden = !page.hasNext;
    }

    async function render() {
        const myGeneration = ++generation;
        const chain = [];
        el.moreError.hidden = true;
        showStatus('Загрузка…', false);
        try {
            if (!root) {
                const described = await getJson(api);
                if (described.type !== 'FOLDER') {
                    throw new LoadError('gone');
                }
                root = { id: described.id, name: described.name };
            }
            chain.push(root);
            // Путь из адреса проверяется по шагам от корня: переход возможен только в найденные дочерние папки.
            let children = await getJson(childrenUrl(root.id));
            for (const id of idsFromHash()) {
                const next = children.find((child) => child.id === id);
                if (!next) {
                    history.replaceState(null, '', hashOf(chain));
                    break;
                }
                chain.push({ id: next.id, name: next.name });
                children = await getJson(childrenUrl(next.id));
            }
            const folder = chain[chain.length - 1];
            const files = await getJson(filesUrl(folder.id, 0));
            if (myGeneration !== generation) {
                return;
            }
            current = folder;
            document.title = folder.name + ' — PhotoCloud';
            el.title.textContent = folder.name;
            drawCrumbs(chain);
            el.folders.replaceChildren(...children.map((child) => folderRow(chain, child)));
            el.files.replaceChildren();
            appendFiles(files);
            el.empty.hidden = children.length > 0 || files.items.length > 0;
            el.status.hidden = true;
            el.content.hidden = false;
        } catch (error) {
            if (myGeneration === generation) {
                showError(error, chain);
            }
        }
    }

    async function loadMore() {
        const myGeneration = generation;
        el.more.disabled = true;
        el.more.textContent = 'Загрузка…';
        el.moreError.hidden = true;
        try {
            const page = await getJson(filesUrl(current.id, nextPage));
            if (myGeneration === generation) {
                appendFiles(page);
            }
        } catch (error) {
            if (myGeneration === generation) {
                el.moreError.textContent = error.kind === 'gone'
                    ? message(error)
                    : 'Не удалось загрузить продолжение списка. Нажмите «Показать ещё», чтобы повторить.';
                el.moreError.hidden = false;
                el.more.hidden = error.kind === 'gone';
            }
        } finally {
            el.more.disabled = false;
            el.more.textContent = 'Показать ещё';
        }
    }

    el.more.addEventListener('click', loadMore);
    window.addEventListener('hashchange', render);
    render();
})();
