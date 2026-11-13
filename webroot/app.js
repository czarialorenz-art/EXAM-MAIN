/* Quiz & Examination System - browser client.
 * Plain JavaScript, no libraries. All rules and permissions are enforced by the Java server;
 * this file only displays data and sends what the user typed. */
'use strict';

const S = { user: null, zone: '', setupNeeded: false, timers: [] };

// =================================================================== helpers

async function api(method, path, body) {
  const opts = { method, headers: {}, credentials: 'same-origin' };
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  } else if (method !== 'GET') {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = '{}';
  }
  let res;
  try {
    res = await fetch(path, opts);
  } catch (e) {
    throw new Error('Cannot reach the server. Check your connection.');
  }
  let data = null;
  try { data = await res.json(); } catch (e) { /* empty body */ }
  if (!res.ok) {
    if (res.status === 401 && S.user && !path.startsWith('/api/login') && !path.startsWith('/api/me/')) {
      S.user = null;
      toast('Your session has ended. Please log in again.', true);
      render();
    }
    throw new Error((data && data.error) || ('Request failed (' + res.status + ')'));
  }
  return data;
}

/** DOM builder: h('div', {class: 'x', onclick: fn}, 'text', child, [more]) */
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'class') el.className = v;
      else if (k === 'value') el.value = v;
      else if (k === 'checked' || k === 'disabled' || k === 'selected' || k === 'multiple') el[k] = !!v;
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  add(el, kids);
  return el;
}
function add(el, kids) {
  if (!Array.isArray(kids)) kids = [kids];
  for (const k of kids) {
    if (k === null || k === undefined || k === false) continue;
    if (Array.isArray(k)) add(el, k);
    else el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
  }
  return el;
}
function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

function toast(msg, error) {
  const t = h('div', { class: 'toast' + (error ? ' error' : '') }, msg);
  document.getElementById('toasts').appendChild(t);
  setTimeout(() => t.remove(), error ? 6000 : 3500);
}

function num(n) {
  if (n === null || n === undefined || isNaN(n)) return '-';
  return Number(n).toFixed(2).replace(/\.?0+$/, '');
}
function pct(n) { return (n === null || n === undefined) ? '-' : Number(n).toFixed(1) + '%'; }
function truncate(s, n) { s = s || ''; return s.length > n ? s.slice(0, n - 1) + '…' : s; }
function go(hash) { if (location.hash === hash) render(); else location.hash = hash; }

function badge(text, kind) { return h('span', { class: 'badge' + (kind ? ' ' + kind : '') }, text); }
function statusBadge(status, label) {
  const kind = { DRAFT: '', PUBLISHED: 'primary', ACTIVE: 'ok', COMPLETED: 'warn', ARCHIVED: '' }[status];
  return badge(label || status, kind);
}

/** Runs an async action from a button, disabling it meanwhile and reporting errors. */
function act(fn, okMsg) {
  return async (ev) => {
    const btn = ev && ev.currentTarget;
    if (btn) btn.disabled = true;
    try {
      await fn(ev);
      if (okMsg) toast(okMsg);
    } catch (e) {
      toast(e.message, true);
    } finally {
      if (btn && btn.isConnected) btn.disabled = false;
    }
  };
}

function field(label, control, help) {
  return h('div', { class: 'field' }, h('label', null, label), control, help ? h('div', { class: 'help' }, help) : null);
}
function input(value, attrs) { return h('input', Object.assign({ type: 'text', value: value == null ? '' : value }, attrs || {})); }
function textarea(value, attrs) { const t = h('textarea', attrs || {}); t.value = value || ''; return t; }
function checkbox(label, checked, attrs) {
  const box = h('input', Object.assign({ type: 'checkbox', checked }, attrs || {}));
  const el = h('label', { class: 'check' }, box, label);
  el.box = box;
  return el;
}
function select(options, value, attrs) {
  const s = h('select', attrs || {});
  for (const o of options) {
    const [v, l] = Array.isArray(o) ? o : [o, o];
    s.appendChild(h('option', { value: v, selected: String(v) === String(value) }, l));
  }
  return s;
}
function table(headers, rows, emptyText) {
  if (!rows.length) return h('div', { class: 'empty' }, emptyText || 'Nothing to show yet.');
  return h('div', { class: 'table-wrap' }, h('table', null,
    h('thead', null, h('tr', null, headers.map(x => typeof x === 'string'
      ? h('th', null, x) : h('th', { class: x.cls }, x.t)))),
    h('tbody', null, rows)));
}

/** Editable list of text rows. */
function listEditor(values, opts) {
  opts = opts || {};
  const wrap = h('div', { class: 'list-editor' });
  const items = h('div');
  const rows = [];
  function addRow(v) {
    const inp = input(v, { placeholder: opts.placeholder || '' });
    const row = h('div', { class: 'item' }, inp,
      h('button', { type: 'button', class: 'ghost sm', title: 'Remove', 'aria-label': 'Remove', onclick: () => {
        if (rows.length <= (opts.min || 0)) return;
        rows.splice(rows.indexOf(inp), 1);
        row.remove();
        if (opts.onchange) opts.onchange();
      } }, '✕'));
    inp.addEventListener('input', () => opts.onchange && opts.onchange());
    rows.push(inp);
    items.appendChild(row);
  }
  (values && values.length ? values : Array(opts.min || 1).fill('')).forEach(addRow);
  wrap.appendChild(items);
  wrap.appendChild(h('button', { type: 'button', class: 'sm', onclick: () => {
    if (opts.max && rows.length >= opts.max) return toast('At most ' + opts.max + ' items.', true);
    addRow(''); if (opts.onchange) opts.onchange();
  } }, '+ ' + (opts.addText || 'Add')));
  wrap.get = () => rows.map(r => r.value.trim()).filter(Boolean);
  return wrap;
}

// =================================================================== modal

function modal(title, body, opts) {
  opts = opts || {};
  const err = h('div', { class: 'error-box hidden' });
  const okBtn = opts.onOk ? h('button', { class: opts.danger ? 'danger solid' : 'primary' }, opts.okText || 'Save') : null;
  const back = h('div', { class: 'modal-back' });
  const box = h('div', { class: 'modal' + (opts.wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('header', null, h('h2', null, title), h('button', { class: 'ghost', 'aria-label': 'Close', onclick: () => close() }, '✕')),
    h('div', { class: 'body' }, err, body),
    h('footer', null, h('button', { onclick: () => close() }, opts.onOk ? (opts.cancelText || 'Cancel') : 'Close'), okBtn));
  back.appendChild(box);
  back.addEventListener('mousedown', (e) => { if (e.target === back) close(); });
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', onKey);
  function close() {
    if (!back.isConnected) return;
    back.remove(); document.removeEventListener('keydown', onKey);
    if (opts.onClose) opts.onClose();
  }
  if (okBtn) {
    okBtn.addEventListener('click', async () => {
      okBtn.disabled = true;
      err.classList.add('hidden');
      try {
        const keep = await opts.onOk();
        if (keep !== true) close();
      } catch (e) {
        err.textContent = e.message;
        err.classList.remove('hidden');
        box.querySelector('.body').scrollTop = 0;
      } finally { okBtn.disabled = false; }
    });
  }
  document.body.appendChild(back);
  const first = box.querySelector('.body input, .body select, .body textarea');
  if (first) first.focus();
  return { close };
}

function confirmBox(title, message, okText, danger) {
  return new Promise((resolve) => {
    let done = false;
    modal(title, h('p', null, message), {
      okText: okText || 'Confirm', danger,
      onOk: () => { done = true; resolve(true); },
      onClose: () => { if (!done) resolve(false); }
    });
  });
}

// =================================================================== routing

const ROUTES = [];
function route(pattern, fn, opts) {
  const names = [];
  const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, n) => { names.push(n); return '([^/]+)'; }) + '$');
  ROUTES.push({ re, names, fn, opts: opts || {} });
}

function navFor(role) {
  if (role === 'ADMIN') return [['#/admin', 'Dashboard'], ['#/users', 'Users'], ['#/subjects', 'Subjects'],
    ['#/classes', 'Classes'], ['#/exams', 'Exams'], ['#/questions', 'Question bank'], ['#/reports', 'Reports']];
  if (role === 'TEACHER') return [['#/teacher', 'Dashboard'], ['#/subjects', 'My subjects'],
    ['#/exams', 'Exams'], ['#/questions', 'Question bank'], ['#/grading', 'Grading'], ['#/reports', 'Reports']];
  return [['#/student', 'My exams'], ['#/my-results', 'My results']];
}
function home() { return navFor(S.user.role)[0][0]; }

function stopTimers() { S.timers.forEach(t => clearInterval(t)); S.timers = []; }

async function render() {
  stopTimers();
  const app = document.getElementById('app');
  if (!S.user) { clear(app); app.appendChild(S.setupNeeded ? setupPage() : loginPage()); return; }
  const hash = location.hash || home();
  let match = null, params = {};
  for (const r of ROUTES) {
    const m = hash.match(r.re);
    if (m) { match = r; r.names.forEach((n, i) => params[n] = decodeURIComponent(m[i + 1])); break; }
  }
  if (!match) { location.hash = home(); return; }
  clear(app);
  let main;
  if (match.opts.bare) {
    main = h('div');
    app.appendChild(main);
  } else {
    main = h('main', { class: 'main' });
    app.appendChild(h('div', { class: 'shell' }, sidebar(hash), main));
  }
  try {
    await match.fn(main, params);
  } catch (e) {
    if (!S.user) return; // the session ended while loading; the login page is already showing
    clear(main).appendChild(h('div', { class: 'card' }, h('div', { class: 'error-box' }, e.message),
      h('a', { href: home() }, 'Back to start')));
  }
}

function sidebar(hash) {
  const u = S.user;
  return h('aside', { class: 'sidebar' },
    h('div', { class: 'brand' }, h('img', { src: 'favicon.svg', alt: '' }), 'Quiz & Exams'),
    h('nav', { class: 'nav' }, navFor(u.role).map(([href, label]) =>
      h('a', { href, class: navActive(hash, href) ? 'active' : null }, label))),
    h('div', { class: 'me' },
      h('div', null, h('strong', null, u.fullName), h('span', { class: 'muted' }, u.roleLabel + ' · ' + u.username)),
      h('div', { class: 'row' },
        h('button', { class: 'sm', onclick: changePassword }, 'Password'),
        h('button', { class: 'sm', onclick: act(async () => {
          await api('POST', '/api/logout'); S.user = null; location.hash = ''; render();
        }) }, 'Log out'))));
}

/** True when a menu item should be highlighted for the current address. */
function navActive(hash, href) {
  if (hash === href || hash.startsWith(href + '/')) return true;
  // "Exams" covers both of its tabs and every single exam page (#/exam/...).
  return href === '#/exams' && hash.startsWith('#/exam/');
}

function pageHead(title, actions, crumb) {
  return [crumb ? h('div', { class: 'crumb' }, h('a', { href: crumb[0] }, '← ' + crumb[1])) : null,
    h('div', { class: 'page-head' }, h('h1', null, title), actions && actions.length ? h('div', { class: 'actions' }, actions) : null)];
}

// =================================================================== login / setup / account

function loginPage() {
  const user = input('', { autocomplete: 'username', id: 'lu' });
  const pass = input('', { type: 'password', autocomplete: 'current-password', id: 'lp' });
  const err = h('div', { class: 'error-box hidden' });
  const btn = h('button', { class: 'primary', type: 'submit' }, 'Log in');
  const form = h('form', { class: 'card login', onsubmit: async (e) => {
    e.preventDefault();
    btn.disabled = true; err.classList.add('hidden');
    try {
      const r = await api('POST', '/api/login', { username: user.value, password: pass.value });
      S.user = r.user; location.hash = ''; render();
    } catch (ex) { err.textContent = ex.message; err.classList.remove('hidden'); pass.value = ''; pass.focus(); }
    finally { btn.disabled = false; }
  } },
    h('div', { class: 'brand' }, h('img', { src: 'favicon.svg', alt: '' }), 'Quiz & Examination System'),
    h('div', { class: 'sub' }, 'Sign in to continue'),
    err, field('Username', user), field('Password', pass), btn);
  setTimeout(() => user.focus(), 0);
  return h('div', { class: 'login-wrap' }, form);
}

function setupPage() {
  const f = { fullName: input(''), email: input('', { type: 'email' }), username: input('', { autocomplete: 'username' }),
    password: input('', { type: 'password', autocomplete: 'new-password' }) };
  const err = h('div', { class: 'error-box hidden' });
  const btn = h('button', { class: 'primary', type: 'submit' }, 'Create administrator');
  return h('div', { class: 'login-wrap' }, h('form', { class: 'card login', onsubmit: async (e) => {
    e.preventDefault(); btn.disabled = true; err.classList.add('hidden');
    try {
      const r = await api('POST', '/api/setup', Object.fromEntries(Object.entries(f).map(([k, v]) => [k, v.value])));
      S.user = r.user; S.setupNeeded = false; location.hash = ''; render();
    } catch (ex) { err.textContent = ex.message; err.classList.remove('hidden'); }
    finally { btn.disabled = false; }
  } },
    h('div', { class: 'brand' }, h('img', { src: 'favicon.svg', alt: '' }), 'First-time setup'),
    h('div', { class: 'sub' }, 'No accounts exist yet. Create the first administrator.'),
    err, field('Full name', f.fullName), field('Email', f.email), field('Username', f.username),
    field('Password', f.password, 'At least 8 characters with letters and numbers.'), btn));
}

function changePassword() {
  const cur = input('', { type: 'password', autocomplete: 'current-password' });
  const p1 = input('', { type: 'password', autocomplete: 'new-password' });
  const p2 = input('', { type: 'password', autocomplete: 'new-password' });
  modal('Change password', h('div', null, field('Current password', cur), field('New password', p1), field('Repeat new password', p2)), {
    okText: 'Change password',
    onOk: async () => {
      await api('POST', '/api/me/password', { current: cur.value, password: p1.value, confirm: p2.value });
      toast('Password changed.');
    }
  });
}

// =================================================================== admin dashboard

route('#/admin', async (main) => {
  const d = await api('GET', '/api/admin/summary');
  add(main, [pageHead('Dashboard', [h('button', { onclick: act(async () => {
    const r = await api('POST', '/api/admin/backup');
    toast('Backup created at ' + r.path);
  }) }, 'Create backup')]),
  h('div', { class: 'stats' }, Object.entries(d.counts).map(([k, v]) => h('div', { class: 'stat' }, h('div', { class: 'v' }, v), h('div', { class: 'l' }, k)))),
  d.warnings.length ? h('div', { class: 'card' }, h('h2', null, 'Data warnings'), h('ul', null, d.warnings.map(w => h('li', null, w)))) : null,
  h('div', { class: 'card' }, h('h2', null, 'System summary'), h('pre', null, d.text),
    h('p', { class: 'muted small', style: { marginTop: '12px' } }, 'Data folder: ' + d.dataDir))]);
});

// =================================================================== users (admin)

route('#/users', async (main) => {
  const q = input('', { type: 'search', placeholder: 'Search name, username, ID, number…' });
  const role = select([['', 'All roles'], ['ADMIN', 'Administrators'], ['TEACHER', 'Teachers'], ['STUDENT', 'Students']], '');
  const out = h('div', { class: 'card' });
  async function load() {
    const users = await api('GET', '/api/users?q=' + encodeURIComponent(q.value) + '&role=' + role.value);
    clear(out).appendChild(table(['Name', 'Username', 'Role', 'Details', 'Email', 'Status', ''], users.map(u => h('tr', null,
      h('td', null, h('strong', null, u.fullName), h('div', { class: 'muted small' }, u.id)),
      h('td', null, u.username), h('td', null, u.roleLabel),
      h('td', { class: 'small' }, u.role === 'STUDENT' ? ['No. ' + u.studentNumber, h('br'), u.yearLevel || ''] : (u.department || '')),
      h('td', { class: 'small' }, u.email || ''),
      h('td', null, u.active ? badge('Active', 'ok') : badge('Inactive', 'danger')),
      h('td', { class: 'nowrap right' },
        h('button', { class: 'sm', onclick: () => editUser(u, load) }, 'Edit'), ' ',
        h('button', { class: 'sm', onclick: act(async () => {
          await api('POST', '/api/users/' + u.id + '/active', { active: !u.active }); await load();
        }, u.active ? 'Account deactivated.' : 'Account activated.') }, u.active ? 'Deactivate' : 'Activate'), ' ',
        h('button', { class: 'sm', onclick: () => resetPassword(u) }, 'Reset password'), ' ',
        h('button', { class: 'sm danger', onclick: act(async () => {
          if (!await confirmBox('Delete user', 'Delete ' + u.fullName + ' (' + u.username + ')? This cannot be undone.', 'Delete', true)) return;
          await api('DELETE', '/api/users/' + u.id); toast('User deleted.'); await load();
        }) }, 'Delete'))
    )), 'No users match.'));
  }
  let t;
  q.addEventListener('input', () => { clearTimeout(t); t = setTimeout(load, 250); });
  role.addEventListener('change', load);
  add(main, [pageHead('Users', [h('button', { class: 'primary', onclick: () => createUser(load) }, '+ New user')]),
    h('div', { class: 'toolbar' }, q, role), out]);
  await load();
});

function createUser(reload) {
  const role = select([['STUDENT', 'Student'], ['TEACHER', 'Teacher'], ['ADMIN', 'Administrator']], 'STUDENT');
  const f = { fullName: input(''), email: input('', { type: 'email' }), username: input(''), password: input('', { type: 'password', autocomplete: 'new-password' }),
    department: input(''), studentNumber: input(''), yearLevel: input('') };
  const teacherBox = h('div', null, field('Department', f.department));
  const studentBox = h('div', { class: 'form-grid' }, field('Student number', f.studentNumber), field('Year level', f.yearLevel));
  const sync = () => { teacherBox.classList.toggle('hidden', role.value !== 'TEACHER'); studentBox.classList.toggle('hidden', role.value !== 'STUDENT'); };
  role.addEventListener('change', sync); sync();
  modal('New user', h('div', null, field('Role', role),
    h('div', { class: 'form-grid' }, field('Full name', f.fullName), field('Email', f.email)),
    h('div', { class: 'form-grid' }, field('Username', f.username), field('Password', f.password, 'At least 8 characters with letters and numbers.')),
    teacherBox, studentBox), {
    okText: 'Create user',
    onOk: async () => {
      const body = { role: role.value };
      for (const [k, v] of Object.entries(f)) body[k] = v.value;
      await api('POST', '/api/users', body); toast('User created.'); await reload();
    }
  });
}

function editUser(u, reload) {
  const f = { username: input(u.username), fullName: input(u.fullName), email: input(u.email || '', { type: 'email' }), roleDetail: input(u.detail || '') };
  modal('Edit ' + u.fullName, h('div', null,
    h('div', { class: 'form-grid' }, field('Full name', f.fullName), field('Username', f.username)),
    field('Email', f.email), u.detailLabel ? field(u.detailLabel, f.roleDetail) : null,
    u.role === 'STUDENT' ? h('p', { class: 'muted small' }, 'Student number: ' + u.studentNumber) : null), {
    onOk: async () => {
      await api('PUT', '/api/users/' + u.id, { username: f.username.value, fullName: f.fullName.value, email: f.email.value, roleDetail: f.roleDetail.value });
      toast('User updated.'); await reload();
    }
  });
}

function resetPassword(u) {
  const p = input('', { type: 'password', autocomplete: 'new-password' });
  modal('Reset password for ' + u.fullName, field('New password', p, 'The user will be logged out and the login lock is cleared.'), {
    okText: 'Reset password',
    onOk: async () => { await api('POST', '/api/users/' + u.id + '/password', { password: p.value }); toast('Password reset.'); }
  });
}

// =================================================================== subjects

route('#/subjects', async (main) => {
  const admin = S.user.role === 'ADMIN';
  const subjects = await api('GET', '/api/subjects');
  add(main, [pageHead(admin ? 'Subjects' : 'My subjects', admin ? [h('button', { class: 'primary', onclick: () => subjectForm(null) }, '+ New subject')] : []),
    h('div', { class: 'card' }, table(['Code', 'Name', 'Teacher', { t: 'Enrolled', cls: 'num' }], subjects.map(s =>
      h('tr', { class: 'clickable', onclick: () => go('#/subject/' + s.id) },
        h('td', null, h('strong', null, s.code)), h('td', null, s.name, s.description ? h('div', { class: 'muted small' }, truncate(s.description, 90)) : null),
        h('td', null, s.teacherName), h('td', { class: 'num' }, s.enrolledCount))),
    admin ? 'No subjects yet. Create one to get started.' : 'No subjects are assigned to you yet. Ask an administrator.'))]);
});

function subjectForm(s) {
  const code = input(s ? s.code : ''), name = input(s ? s.name : ''), desc = textarea(s ? s.description : '');
  modal(s ? 'Edit subject' : 'New subject', h('div', null, h('div', { class: 'form-grid' }, field('Code', code, 'e.g. CS101'), field('Name', name)), field('Description', desc)), {
    onOk: async () => {
      const body = { code: code.value, name: name.value, description: desc.value };
      const r = s ? await api('PUT', '/api/subjects/' + s.id, body) : await api('POST', '/api/subjects', body);
      toast(s ? 'Subject updated.' : 'Subject created.');
      go('#/subject/' + r.id);
    }
  });
}

route('#/subject/:id', async (main, p) => {
  const admin = S.user.role === 'ADMIN';
  const [s, students, classes, teachers] = await Promise.all([
    api('GET', '/api/subjects/' + p.id), api('GET', '/api/students'), api('GET', '/api/classes'),
    admin ? api('GET', '/api/teachers') : Promise.resolve([])]);
  const reload = () => render();
  const enrolled = new Set(s.enrolledIds);
  const pickStudent = select([['', 'Choose a student…']].concat(students.filter(x => !enrolled.has(x.id))
    .map(x => [x.id, x.fullName + ' (' + x.studentNumber + ')'])), '');
  const pickClass = select([['', 'Choose a class…']].concat(classes.map(c => [c.id, c.name + ' (' + c.size + ')'])), '');

  const actions = admin ? [h('button', { onclick: () => subjectForm(s) }, 'Edit'),
    h('button', { class: 'danger', onclick: act(async () => {
      if (!await confirmBox('Delete subject', 'Delete ' + s.code + '?', 'Delete', true)) return;
      await api('DELETE', '/api/subjects/' + s.id); toast('Subject deleted.'); go('#/subjects');
    }) }, 'Delete')] : [];

  add(main, [pageHead(s.code + ' · ' + s.name, actions, ['#/subjects', 'Subjects']),
    s.description ? h('p', { class: 'muted' }, s.description) : null,
    h('div', { class: 'grid cols-2' },
      h('div', { class: 'card' }, h('h2', null, 'Teacher'),
        admin ? h('div', { class: 'row' }, (() => {
          const t = select([['', '(unassigned)']].concat(teachers.filter(x => x.active).map(x => [x.id, x.fullName])), s.teacherId || '');
          return [t, h('button', { class: 'primary', onclick: act(async () => {
            await api('POST', '/api/subjects/' + s.id + '/teacher', { teacherId: t.value || null }); reload();
          }, 'Teacher updated.') }, 'Save')];
        })()) : h('p', null, s.teacherName)),
      h('div', { class: 'card' }, h('h2', null, 'Enrol students'),
        h('div', { class: 'row', style: { marginBottom: '10px' } }, h('div', { class: 'grow' }, pickStudent),
          h('button', { class: 'primary', onclick: act(async () => {
            if (!pickStudent.value) throw new Error('Choose a student first.');
            await api('POST', '/api/subjects/' + s.id + '/enroll', { studentId: pickStudent.value }); reload();
          }, 'Student enrolled.') }, 'Enrol')),
        h('div', { class: 'row' }, h('div', { class: 'grow' }, pickClass),
          h('button', { onclick: act(async () => {
            if (!pickClass.value) throw new Error('Choose a class first.');
            const r = await api('POST', '/api/subjects/' + s.id + '/enroll-class', { classId: pickClass.value });
            toast(r.added + ' student(s) newly enrolled.'); reload();
          }) }, 'Enrol whole class')))),
    h('div', { class: 'card' }, h('h2', null, 'Enrolled students (' + s.students.length + ')'),
      table(['Name', 'Student number', 'Year level', ''], s.students.map(x => h('tr', null,
        h('td', null, x.fullName), h('td', null, x.studentNumber), h('td', null, x.yearLevel || ''),
        h('td', { class: 'right' }, h('button', { class: 'sm danger', onclick: act(async () => {
          await api('POST', '/api/subjects/' + s.id + '/unenroll', { studentId: x.id }); reload();
        }, 'Student removed.') }, 'Remove')))), 'No students enrolled yet.'))]);
});

// =================================================================== classes (admin)

route('#/classes', async (main) => {
  const [classes, students] = await Promise.all([api('GET', '/api/classes'), api('GET', '/api/students')]);
  const name = input('', { placeholder: 'New class name, e.g. BSIT 2-A' });
  add(main, [pageHead('Classes'),
    h('div', { class: 'card' }, h('form', { class: 'row', onsubmit: (e) => { e.preventDefault(); } },
      h('div', { class: 'grow' }, name),
      h('button', { class: 'primary', onclick: act(async () => {
        await api('POST', '/api/classes', { name: name.value }); render();
      }, 'Class created.') }, '+ Create class'))),
    classes.length ? h('div', { class: 'grid cols-2' }, classes.map(c => {
      const members = new Set(c.students.map(x => x.id));
      const pick = select([['', 'Add a student…']].concat(students.filter(x => !members.has(x.id)).map(x => [x.id, x.fullName + ' (' + x.studentNumber + ')'])), '');
      return h('div', { class: 'card' },
        h('div', { class: 'row' }, h('h2', { class: 'grow', style: { margin: 0 } }, c.name), badge(c.size + ' students'),
          h('button', { class: 'sm danger', onclick: act(async () => {
            if (!await confirmBox('Delete class', 'Delete class ' + c.name + '?', 'Delete', true)) return;
            await api('DELETE', '/api/classes/' + c.id); toast('Class deleted.'); render();
          }) }, 'Delete')),
        h('div', { class: 'row', style: { margin: '12px 0' } }, h('div', { class: 'grow' }, pick),
          h('button', { onclick: act(async () => {
            if (!pick.value) throw new Error('Choose a student first.');
            await api('POST', '/api/classes/' + c.id + '/add', { studentId: pick.value }); render();
          }) }, 'Add')),
        c.students.length ? h('div', null, c.students.map(x => h('div', { class: 'row', style: { padding: '4px 0', borderTop: '1px solid var(--border)' } },
          h('span', { class: 'grow' }, x.fullName, h('span', { class: 'muted small' }, '  ' + x.studentNumber)),
          h('button', { class: 'sm ghost danger', onclick: act(async () => {
            await api('POST', '/api/classes/' + c.id + '/remove', { studentId: x.id }); render();
          }) }, 'Remove')))) : h('p', { class: 'muted' }, 'No members yet.'));
    })) : h('div', { class: 'card empty' }, 'No classes yet.')]);
});

// =================================================================== question bank

const QTYPES = [['MULTIPLE_CHOICE', 'Multiple Choice'], ['MULTIPLE_SELECT', 'Multiple Select'], ['TRUE_FALSE', 'True or False'],
  ['IDENTIFICATION', 'Identification'], ['FILL_IN_THE_BLANKS', 'Fill in the Blanks'], ['ENUMERATION', 'Enumeration'],
  ['ESSAY', 'Essay'], ['MATCHING', 'Matching'], ['NUMERICAL', 'Numerical Answer']];
const DIFFS = [['EASY', 'Easy'], ['MEDIUM', 'Medium'], ['HARD', 'Hard']];

// The Question Bank has its own page (menu item below "Exams"). The old tab address is sent to it.
route('#/questions', async (main) => {
  add(main, pageHead('Question bank'));
  await questionBankTab(main);
});
route('#/exams/bank', async () => { location.replace('#/questions'); });

/** Filter choices of the bank, remembered per exam so they survive a page refresh. */
const bankFilters = {};

/**
 * Question Bank: list, filters, search, and add / edit / delete.
 * Inside an exam (ctx.exam) it starts on that exam's subject, offers "Add to exam", and calls
 * ctx.onChange after a change so the exam page refreshes and stays on its Questions tab.
 */
async function questionBankTab(main, ctx) {
  ctx = ctx || {};
  const exam = ctx.exam;
  const teacher = S.user.role === 'TEACHER';
  const saved = (exam && bankFilters[exam.id]) || { sub: exam ? exam.subjectId : '', cls: '', type: '', diff: '', q: '' };
  const [subjects, classes] = await Promise.all([api('GET', '/api/subjects'), api('GET', '/api/classes')]);
  const fSub = select([['', 'All subjects']].concat(subjects.map(s => [s.id, s.code])), saved.sub);
  const fCls = select([['', 'All classes'], ['none', 'General (no class)']].concat(classes.map(c => [c.id, c.name])), saved.cls);
  const fType = select([['', 'All types']].concat(QTYPES), saved.type);
  const fDiff = select([['', 'Any difficulty']].concat(DIFFS), saved.diff);
  const fQ = input(saved.q, { type: 'search', placeholder: 'Search text, ID, category…' });
  const out = h('div', { class: exam ? null : 'card' });
  const inExam = new Set(exam ? exam.questions.map(q => q.id) : []);
  const canAdd = exam && exam.owner && exam.status === 'DRAFT';
  const remember = () => { if (exam) bankFilters[exam.id] = { sub: fSub.value, cls: fCls.value, type: fType.value, diff: fDiff.value, q: fQ.value }; };
  // After a change: refresh the whole exam page when inside an exam, otherwise just the list.
  const changed = async () => { remember(); if (ctx.onChange) await ctx.onChange(); else await load(); };
  async function load() {
    const qs = await api('GET', '/api/questions?subjectId=' + fSub.value + '&classId=' + fCls.value + '&type=' + fType.value + '&difficulty=' + fDiff.value + '&q=' + encodeURIComponent(fQ.value));
    clear(out).appendChild(table(['ID', 'Subject', 'Class', 'Type', 'Question', { t: 'Points', cls: 'num' }, 'Difficulty', ''], qs.map(q => h('tr', null,
      h('td', { class: 'small nowrap' }, q.id), h('td', null, q.subjectCode), h('td', null, classBadge(q)), h('td', { class: 'small' }, q.typeLabel),
      h('td', null, truncate(q.text, 110), h('div', { class: 'muted small' }, q.category)),
      h('td', { class: 'num' }, num(q.points)), h('td', null, badge(q.difficulty.charAt(0) + q.difficulty.slice(1).toLowerCase())),
      h('td', { class: 'nowrap right' },
        inExam.has(q.id) ? [badge('In this exam', 'ok'), ' '] : null,
        canAdd && !inExam.has(q.id) && q.subjectId === exam.subjectId ? [h('button', { class: 'sm primary', onclick: act(async () => {
          await api('POST', '/api/exams/' + exam.id + '/questions', { questionIds: [q.id] });
          toast('Question ' + q.id + ' added to the exam.'); await changed();
        }) }, 'Add to exam'), ' '] : null,
        h('button', { class: 'sm', onclick: () => viewQuestion(q) }, 'View'),
        teacher ? [' ', h('button', { class: 'sm', onclick: () => questionForm(q, subjects, changed) }, 'Edit'), ' ',
          h('button', { class: 'sm danger', onclick: act(async () => {
            if (!await confirmBox('Delete question', 'Delete question ' + q.id + '? It will also be removed from draft exams. Published exams keep their own copy.', 'Delete', true)) return;
            const r = await api('DELETE', '/api/questions/' + q.id);
            toast('Question deleted' + (r.draftsChanged ? ' and removed from ' + r.draftsChanged + ' draft exam(s).' : '.')); await changed();
          }) }, 'Delete')] : null)
    )), teacher ? 'No questions match. Click “New question” to write one.' : 'No questions found.'));
  }
  let t;
  [fSub, fCls, fType, fDiff].forEach(x => x.addEventListener('change', () => { remember(); load(); }));
  fQ.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { remember(); load(); }, 250); });
  const newBtn = teacher ? h('button', { class: 'primary', style: { marginLeft: 'auto' }, onclick: () => {
    if (!subjects.length) return toast('You need an assigned subject before writing questions.', true);
    const defaultClass = fCls.value && fCls.value !== 'none' ? fCls.value
      : (exam && exam.assignedClassIds.length === 1 ? exam.assignedClassIds[0] : null);
    questionForm(null, subjects, changed, exam ? exam.subjectId : null, defaultClass);
  } }, '+ New question') : null;
  add(main, [h('div', { class: 'toolbar' }, fSub, fCls, fType, fDiff, fQ, newBtn), out]);
  await load();
}

/** Shows which class a question belongs to ("General" when it is not tied to one). */
function classBadge(q) {
  return q.classId ? badge(q.className, 'primary') : badge('General');
}

function viewQuestion(q) {
  modal(q.id + ' · ' + q.typeLabel, h('div', null,
    h('p', { style: { whiteSpace: 'pre-wrap' } }, q.text),
    q.choices ? h('ol', { type: 'A' }, q.choices.map(c => h('li', null, c))) : null,
    q.left ? h('div', { class: 'grid cols-2' }, h('div', null, h('strong', null, 'Column A'), h('ol', null, q.left.map(x => h('li', null, x)))),
      h('div', null, h('strong', null, 'Column B'), h('ol', { type: 'A' }, q.right.map(x => h('li', null, x))))) : null,
    h('dl', { class: 'kv' }, h('dt', null, 'Answer key'), h('dd', null, q.answerKey),
      h('dt', null, 'Points'), h('dd', null, num(q.points)), h('dt', null, 'Subject'), h('dd', null, q.subjectCode),
      h('dt', null, 'Class'), h('dd', null, q.classId ? q.className : 'General (any class)'),
      h('dt', null, 'Category'), h('dd', null, q.category), h('dt', null, 'Difficulty'), h('dd', null, q.difficulty),
      q.explanation ? [h('dt', null, 'Explanation'), h('dd', null, q.explanation)] : null)), { wide: true });
}

/** Create or edit a question of any of the nine types. */
async function questionForm(q, subjects, reload, fixedSubjectId, defaultClassId) {
  let classes;
  try { classes = await api('GET', '/api/classes'); } catch (e) { return toast(e.message, true); }
  const editing = !!q;
  q = q || { type: 'MULTIPLE_CHOICE', subjectId: fixedSubjectId || (subjects[0] && subjects[0].id), classId: defaultClassId || null, points: 1, difficulty: 'MEDIUM', category: '' };
  const type = select(QTYPES, q.type, { disabled: editing });
  const subject = select(subjects.map(s => [s.id, s.code + ' · ' + s.name]), q.subjectId, { disabled: !!fixedSubjectId });
  const klass = select([['', 'General — can be used with any class']].concat(classes.map(c => [c.id, c.name])), q.classId || '');
  const text = textarea(q.text, { rows: 3 });
  const points = input(q.points, { type: 'number', min: '0.25', step: '0.25' });
  const category = input(q.category, { placeholder: 'General' });
  const difficulty = select(DIFFS, q.difficulty);
  const explanation = textarea(q.explanation, { rows: 2, placeholder: 'Shown to students with their released result (optional)' });
  const specific = h('div');
  let getSpecific = () => ({});

  function choiceRows(multi) {
    const wrap = h('div');
    const rows = [];
    const correctSet = new Set(multi ? (q.correctList || []) : (q.correct !== undefined ? [q.correct] : [0]));
    const list = h('div');
    function addRow(text, correct) {
      const mark = h('input', { type: multi ? 'checkbox' : 'radio', name: 'correct', checked: correct, title: 'Correct answer' });
      const inp = input(text, { placeholder: 'Choice text' });
      const row = h('div', { class: 'item' }, h('span', { class: 'letter', style: { width: '1.4em', fontWeight: 700 } }), mark, inp,
        h('button', { type: 'button', class: 'ghost sm', 'aria-label': 'Remove choice', onclick: () => {
          if (rows.length <= 2) return toast('At least 2 choices are needed.', true);
          rows.splice(rows.findIndex(r => r.inp === inp), 1); row.remove(); letters();
        } }, '✕'));
      rows.push({ mark, inp }); list.appendChild(row); letters();
    }
    function letters() { list.querySelectorAll('.letter').forEach((el, i) => el.textContent = String.fromCharCode(65 + i)); }
    const initial = q.choices && q.choices.length ? q.choices : ['', '', '', ''];
    initial.forEach((c, i) => addRow(c, correctSet.has(i)));
    wrap.appendChild(h('label', null, multi ? 'Choices (tick every correct answer)' : 'Choices (select the correct answer)'));
    wrap.appendChild(h('div', { class: 'list-editor' }, list, h('button', { type: 'button', class: 'sm', onclick: () => {
      if (rows.length >= 6) return toast('At most 6 choices.', true); addRow('', false);
    } }, '+ Add choice')));
    wrap.get = () => {
      const choices = rows.map(r => r.inp.value);
      const marked = rows.map((r, i) => r.mark.checked ? i : -1).filter(i => i >= 0);
      return { choices, marked };
    };
    return wrap;
  }

  function build() {
    clear(specific);
    const t = type.value;
    if (t === 'MULTIPLE_CHOICE' || t === 'MULTIPLE_SELECT') {
      const multi = t === 'MULTIPLE_SELECT';
      const rows = choiceRows(multi);
      const partial = checkbox('Give partial credit (right picks minus wrong picks)', !!q.partialCredit);
      add(specific, [rows, multi ? partial : null]);
      getSpecific = () => {
        const { choices, marked } = rows.get();
        if (!marked.length) throw new Error('Mark the correct answer.');
        return multi ? { choices, correctList: marked, partialCredit: partial.box.checked } : { choices, correct: marked[0] };
      };
    } else if (t === 'TRUE_FALSE') {
      const val = select([['true', 'True'], ['false', 'False']], String(q.correctBool === undefined ? true : q.correctBool));
      add(specific, field('Correct answer', val));
      getSpecific = () => ({ correctBool: val.value === 'true' });
    } else if (t === 'IDENTIFICATION') {
      const list = listEditor(q.accepted, { placeholder: 'Accepted answer', addText: 'Add accepted answer', min: 1 });
      const cs = checkbox('Capitalisation matters', !!q.caseSensitive);
      add(specific, [field('Accepted answers', list, 'Any of these is marked correct. Extra spaces are ignored.'), cs]);
      getSpecific = () => ({ accepted: list.get(), caseSensitive: cs.box.checked });
    } else if (t === 'FILL_IN_THE_BLANKS') {
      const box = h('div');
      const existing = (q.blanks || []).map(b => b.join(' | '));
      let inputs = [];
      const refresh = () => {
        const n = (text.value.match(/_{3,}/g) || []).length;
        const vals = inputs.map(i => i.value);
        clear(box); inputs = [];
        if (!n) { box.appendChild(h('div', { class: 'info-box' }, 'Write ___ (three underscores) in the question text for each blank.')); return; }
        for (let i = 0; i < n; i++) {
          const inp = input(vals[i] !== undefined ? vals[i] : (existing[i] || ''), { placeholder: 'e.g. Paris | paris city' });
          inputs.push(inp); box.appendChild(field('Blank ' + (i + 1) + ' accepted answers', inp));
        }
        box.appendChild(h('div', { class: 'help muted small' }, 'Separate alternative answers with |'));
      };
      text.addEventListener('input', refresh);
      refresh();
      specific.appendChild(box);
      getSpecific = () => ({ blanks: inputs.map(i => i.value.split('|').map(s => s.trim()).filter(Boolean)) });
    } else if (t === 'ENUMERATION') {
      const list = listEditor(q.items, { placeholder: 'Expected item', addText: 'Add item', min: 1 });
      const order = checkbox('Order matters', !!q.orderMatters);
      const manual = checkbox('Grade manually instead of automatically', !!q.manualGrading);
      add(specific, [field('Expected items', list), order, manual]);
      getSpecific = () => ({ items: list.get(), orderMatters: order.box.checked, manualGrading: manual.box.checked });
    } else if (t === 'ESSAY') {
      const min = input(q.minWords || 0, { type: 'number', min: '0', step: '1' });
      const rubric = textarea(q.rubric, { rows: 3, placeholder: 'What a good answer includes (seen by teachers only)' });
      add(specific, [field('Recommended minimum words', min), field('Rubric', rubric)]);
      getSpecific = () => ({ minWords: min.value || 0, rubric: rubric.value });
    } else if (t === 'MATCHING') {
      const pairs = h('div', { class: 'list-editor' });
      const pairRows = [];
      const usedRight = new Set();
      const initialPairs = [];
      if (q.left) q.left.forEach((l, i) => { initialPairs.push([l, q.right[q.key[i]]]); usedRight.add(q.key[i]); });
      const distractors = q.right ? q.right.filter((_, i) => !usedRight.has(i)) : [];
      const addPair = (l, r) => {
        const a = input(l, { placeholder: 'Column A item' }), b = input(r, { placeholder: 'Its match (column B)' });
        const row = h('div', { class: 'item' }, a, h('span', { class: 'muted' }, '→'), b, h('button', { type: 'button', class: 'ghost sm', 'aria-label': 'Remove pair', onclick: () => {
          if (pairRows.length <= 2) return toast('At least 2 pairs are needed.', true);
          pairRows.splice(pairRows.findIndex(p => p.a === a), 1); row.remove();
        } }, '✕'));
        pairRows.push({ a, b }); pairs.insertBefore(row, pairs.lastChild);
      };
      pairs.appendChild(h('button', { type: 'button', class: 'sm', onclick: () => pairRows.length >= 10 ? toast('At most 10 pairs.', true) : addPair('', '') }, '+ Add pair'));
      (initialPairs.length ? initialPairs : [['', ''], ['', ''], ['', '']]).forEach(([l, r]) => addPair(l, r));
      const extra = listEditor(distractors, { placeholder: 'Extra wrong option (optional)', addText: 'Add distractor' });
      add(specific, [field('Correct pairs', pairs, 'Column B is shown in shuffled order when choice shuffling is on.'), field('Distractors for column B', extra)]);
      getSpecific = () => {
        const rows = pairRows.filter(p => p.a.value.trim() || p.b.value.trim());
        const left = rows.map(p => p.a.value.trim()), right = rows.map(p => p.b.value.trim()).concat(extra.get());
        return { left, right, key: rows.map((_, i) => i) };
      };
    } else if (t === 'NUMERICAL') {
      const value = input(q.value !== undefined ? q.value : '', { type: 'number', step: 'any' });
      const tol = input(q.tolerance || 0, { type: 'number', step: 'any', min: '0' });
      add(specific, h('div', { class: 'form-grid' }, field('Correct value', value), field('Tolerance (±)', tol)));
      getSpecific = () => ({ value: value.value, tolerance: tol.value || 0 });
    }
  }
  type.addEventListener('change', build);
  build();

  modal(editing ? 'Edit question ' + q.id : 'New question', h('div', null,
    h('div', { class: 'form-grid' }, field('Type', type), field('Subject', subject)),
    field('Class', klass, 'Pick the class this question is for. It can then only be published in exams assigned to that class.'),
    field('Question text', text, 'For Fill in the Blanks, write ___ (three underscores) where each blank goes.'),
    h('div', { class: 'form-grid' }, field('Points', points), field('Difficulty', difficulty), field('Category', category)),
    h('hr'), specific, h('hr'), field('Explanation', explanation),
    editing ? h('p', { class: 'muted small' }, 'Published exams keep the version they were published with.') : null), {
    wide: true, okText: editing ? 'Save changes' : 'Create question',
    onOk: async () => {
      const body = Object.assign({ type: type.value, subjectId: subject.value, classId: klass.value || null, text: text.value, points: points.value,
        category: category.value, difficulty: difficulty.value, explanation: explanation.value }, getSpecific());
      const r = editing ? await api('PUT', '/api/questions/' + q.id, body) : await api('POST', '/api/questions', body);
      toast(editing ? 'Question saved.' : 'Question ' + r.id + ' created.');
      if (reload) await reload(r);
    }
  });
}

// =================================================================== exams (teacher / admin)

// Exams page: the list of exams. The Question Bank is on each exam's Questions tab.
route('#/exams', async (main) => {
  const teacher = S.user.role === 'TEACHER';
  const exams = await api('GET', '/api/exams');
  add(main, [pageHead('Exams', teacher ? [h('button', { class: 'primary', onclick: newExam }, '+ New exam')] : []),
    h('div', { class: 'card' }, table(['Title', 'Subject', 'Status', { t: 'Questions', cls: 'num' }, { t: 'Points', cls: 'num' }, 'Timer', 'Schedule'].concat(teacher ? [] : ['Teacher']),
      exams.map(e => h('tr', { class: 'clickable', onclick: () => go('#/exam/' + e.id) },
        h('td', null, h('strong', null, e.title)), h('td', null, e.subjectCode), h('td', null, statusBadge(e.effectiveStatus, e.statusLabel)),
        h('td', { class: 'num' }, e.questionCount), h('td', { class: 'num' }, num(e.totalPoints)), h('td', { class: 'small' }, e.timerLabel),
        h('td', { class: 'small' }, e.opensAt || e.closesAt ? [e.opensLabel, ' → ', e.closesLabel] : 'Any time'),
        teacher ? null : h('td', null, e.teacherName))),
      teacher ? 'No exams yet. Click “New exam” to create one.' : 'No exams yet.'))]);
});

async function newExam() {
  const subjects = await api('GET', '/api/subjects');
  if (!subjects.length) return toast('You need an assigned subject before creating exams.', true);
  const sub = select(subjects.map(s => [s.id, s.code + ' · ' + s.name]), subjects[0].id);
  const title = input(''), instr = textarea('', { rows: 3 });
  modal('New exam', h('div', null, field('Subject', sub), field('Title', title), field('Instructions', instr)), {
    okText: 'Create exam',
    onOk: async () => {
      const e = await api('POST', '/api/exams', { subjectId: sub.value, title: title.value, instructions: instr.value });
      toast('Exam created as a draft.'); go('#/exam/' + e.id);
    }
  });
}

const examTabs = {};
route('#/exam/:id', async (main, p) => {
  const e = await api('GET', '/api/exams/' + p.id);
  const owner = e.owner;
  const draft = e.status === 'DRAFT';
  const tabNames = ['Overview', 'Questions', 'Assignment', 'Results', 'Statistics'];
  let tab = examTabs[p.id] || 'Overview';
  const tabBar = h('div', { class: 'tabs', role: 'tablist' });
  const body = h('div');
  const reload = () => render();

  async function lifecycle(action, msg, confirmMsg, danger) {
    if (confirmMsg && !await confirmBox(msg, confirmMsg, msg, danger)) return;
    await api('POST', '/api/exams/' + e.id + '/lifecycle', { action });
    toast(msg + ' done.'); reload();
  }
  const actions = [];
  if (owner) {
    if (draft) actions.push(h('button', { class: 'primary', onclick: act(() => lifecycle('publish', 'Publish', 'Publish this exam? Its questions are copied as they are now, and assigned students will see it.')) }, 'Publish'));
    if (e.status === 'PUBLISHED') {
      actions.push(h('button', { onclick: act(() => lifecycle('unpublish', 'Return to draft', 'Return this exam to draft? Only possible while nobody has started it.')) }, 'Return to draft'));
      actions.push(h('button', { onclick: act(() => lifecycle('close', 'Close exam', 'Close this exam now? Open attempts will be finished.', true)) }, 'Close now'));
    }
    if (e.status !== 'ARCHIVED' && !draft) actions.push(h('button', { onclick: act(() => lifecycle('archive', 'Archive', 'Archive this exam?')) }, 'Archive'));
    actions.push(h('button', { class: 'danger', onclick: act(async () => {
      if (!await confirmBox('Delete exam', 'Delete “' + e.title + '”? This is only possible when nobody has attempted it.', 'Delete', true)) return;
      await api('DELETE', '/api/exams/' + e.id); toast('Exam deleted.'); go('#/exams');
    }) }, 'Delete'));
  }

  function showTab(name) {
    tab = name; examTabs[p.id] = name;
    clear(tabBar);
    tabNames.forEach(n => tabBar.appendChild(h('button', { role: 'tab', class: n === tab ? 'active' : null, onclick: () => showTab(n) }, n)));
    clear(body);
    const fn = { Overview: examOverview, Questions: examQuestions, Assignment: examAssignment, Results: examResults, Statistics: examStats }[name];
    fn(body, e, owner, reload).catch(err => body.appendChild(h('div', { class: 'error-box' }, err.message)));
  }

  add(main, [pageHead(e.title, actions, ['#/exams', 'Exams']),
    h('div', { class: 'row', style: { marginBottom: '14px' } }, statusBadge(e.effectiveStatus, e.statusLabel), badge(e.subjectCode),
      badge(e.questionCount + ' questions · ' + num(e.totalPoints) + ' pts'), badge(e.timerLabel),
      owner ? null : h('span', { class: 'muted small' }, 'Teacher: ' + e.teacherName + ' (read-only)')),
    tabBar, body]);
  showTab(tab);
});

async function examOverview(body, e, owner, reload) {
  const draft = e.status === 'DRAFT';
  const editable = owner && draft;
  const title = input(e.title, { disabled: !editable }), instr = textarea(e.instructions, { rows: 3, disabled: !editable });
  const pass = input(e.passingPercentage, { type: 'number', min: 0, max: 100, step: 'any', disabled: !editable });
  const att = input(e.maxAttempts, { type: 'number', min: 1, max: 10, step: 1, disabled: !editable });
  const shQ = checkbox('Shuffle question order', e.shuffleQuestions, { disabled: !editable });
  const shC = checkbox('Shuffle choices (and matching column B)', e.shuffleChoices, { disabled: !editable });
  const auto = checkbox('Release results automatically when fully graded', e.autoReleaseResults, { disabled: !editable });

  const tEnabled = checkbox('Use a timer', e.timer.enabled, { disabled: !editable });
  const preset = select([['', 'Custom…'], ['5m', '5 minutes'], ['10m', '10 minutes'], ['15m', '15 minutes'], ['20m', '20 minutes'], ['30m', '30 minutes'],
    ['45m', '45 minutes'], ['1h', '1 hour'], ['1h30m', '1 hour 30 minutes'], ['2h', '2 hours']], '', { disabled: !editable });
  const dur = input(e.timer.minutes ? (Math.floor(e.timer.minutes / 60) ? Math.floor(e.timer.minutes / 60) + 'h' : '') + (e.timer.minutes % 60 ? e.timer.minutes % 60 + 'm' : '') : '30m', { disabled: !editable, placeholder: 'e.g. 50, 1h15m, 01:15:30' });
  preset.addEventListener('change', () => { if (preset.value) dur.value = preset.value; });
  const tAuto = checkbox('Submit automatically when time runs out', e.timer.autoSubmit, { disabled: !editable });
  const tPause = checkbox('Allow students to pause', e.timer.allowPause, { disabled: !editable });
  const syncT = () => [preset, dur, tAuto.box, tPause.box].forEach(x => x.disabled = !editable || !tEnabled.box.checked);
  tEnabled.box.addEventListener('change', syncT); syncT();

  const schedEditable = owner && (draft || e.status === 'PUBLISHED');
  const opens = input(e.opensAt || '', { type: 'datetime-local', disabled: !schedEditable });
  const closes = input(e.closesAt || '', { type: 'datetime-local', disabled: !schedEditable });

  add(body, [
    !draft && owner ? h('div', { class: 'info-box' }, 'Settings and questions can only be changed while the exam is a draft. The schedule and assignment can still change while it is published.') : null,
    h('div', { class: 'grid cols-2' },
      h('div', { class: 'card' }, h('h2', null, 'Details'), field('Title', title), field('Instructions', instr),
        h('div', { class: 'form-grid' }, field('Passing percentage', pass), field('Maximum attempts', att)), shQ, shC, auto,
        editable ? h('div', { class: 'row', style: { marginTop: '12px' } }, h('button', { class: 'primary', onclick: act(async () => {
          await api('PUT', '/api/exams/' + e.id, { title: title.value, instructions: instr.value, passingPercentage: pass.value, maxAttempts: att.value,
            shuffleQuestions: shQ.box.checked, shuffleChoices: shC.box.checked, autoReleaseResults: auto.box.checked });
          reload();
        }, 'Details saved.') }, 'Save details')) : null),
      h('div', null,
        h('div', { class: 'card' }, h('h2', null, 'Timer'), tEnabled, h('div', { class: 'form-grid' }, field('Preset', preset), field('Duration', dur)), tAuto, tPause,
          h('p', { class: 'muted small' }, 'The deadline is fixed when a student starts and is enforced by the server.'),
          editable ? h('button', { class: 'primary', onclick: act(async () => {
            await api('PUT', '/api/exams/' + e.id + '/timer', { enabled: tEnabled.box.checked, duration: dur.value, autoSubmit: tAuto.box.checked, allowPause: tPause.box.checked });
            reload();
          }, 'Timer saved.') }, 'Save timer') : null),
        h('div', { class: 'card' }, h('h2', null, 'Schedule'), h('div', { class: 'form-grid' }, field('Opens', opens), field('Closes', closes)),
          h('p', { class: 'muted small' }, 'Leave empty for no limit. Times are in the server’s time zone (' + S.zone + ').'),
          schedEditable ? h('button', { class: 'primary', onclick: act(async () => {
            await api('PUT', '/api/exams/' + e.id + '/schedule', { opensAt: opens.value, closesAt: closes.value }); reload();
          }, 'Schedule saved.') }, 'Save schedule') : null)))]);
}

async function examQuestions(body, e, owner, reload) {
  const editable = owner && e.status === 'DRAFT';
  const addBtn = editable ? h('button', { class: 'primary', onclick: act(async () => {
    const [bank, subjects, allClasses] = await Promise.all([api('GET', '/api/questions?subjectId=' + e.subjectId), api('GET', '/api/subjects'), api('GET', '/api/classes')]);
    const inExam = new Set(e.questions.map(q => q.id));
    const avail = bank.filter(q => !inExam.has(q.id));
    const assigned = new Set(e.assignedClassIds);
    const fits = (q) => !q.classId || assigned.has(q.classId);
    const boxes = avail.map(q => ({ q, c: checkbox(h('span', null, h('strong', null, q.id + ' '), classBadge(q), ' ', badge(q.typeLabel), ' ', truncate(q.text, 90),
      h('span', { class: 'muted' }, ' · ' + num(q.points) + ' pt'), fits(q) ? null : [' ', badge('class not assigned to this exam', 'warn')]), false) }));
    const showAll = checkbox('Also show questions from classes this exam is not assigned to', false);
    const empty = h('p', { class: 'muted' });
    const syncList = () => {
      let shown = 0;
      boxes.forEach(b => { const vis = showAll.box.checked || fits(b.q); b.c.classList.toggle('hidden', !vis); if (!vis) b.c.box.checked = false; if (vis) shown++; });
      empty.textContent = shown ? '' : (avail.length ? 'No general questions or questions for this exam’s classes are left. Tick the box above to see the others.' : 'Every question for this subject is already in the exam.');
    };
    showAll.box.addEventListener('change', syncList);
    const classNote = assigned.size
      ? 'This exam is assigned to: ' + e.assignedClassIds.map(id => (allClasses.find(c => c.id === id) || {}).name || id).join(', ') + '. General questions fit any exam.'
      : 'This exam is not assigned to any class yet, so only general questions fit. Assign classes on the Assignment tab to use class questions.';
    const list = h('div', null, boxes.map(b => b.c), empty);
    syncList();
    const m = modal('Add questions from the bank', h('div', null, h('div', { class: 'info-box' }, classNote), showAll, h('hr'), list, h('hr'),
      h('button', { class: 'sm', onclick: () => { m.close(); questionForm(null, subjects, async (created) => {
        await api('POST', '/api/exams/' + e.id + '/questions', { questionIds: [created.id] }); reload();
      }, e.subjectId, assigned.size === 1 ? e.assignedClassIds[0] : null); } }, '+ Write a new question instead')), {
      wide: true, okText: 'Add selected',
      onOk: async () => {
        const ids = boxes.filter(b => b.c.box.checked).map(b => b.q.id);
        if (!ids.length) throw new Error('Tick at least one question.');
        await api('POST', '/api/exams/' + e.id + '/questions', { questionIds: ids });
        toast(ids.length + ' question(s) added.'); reload();
      }
    });
  }) }, '+ Add questions') : null;
  add(body, h('div', { class: 'card' }, h('div', { class: 'row', style: { marginBottom: '10px' } }, h('h2', { class: 'grow', style: { margin: 0 } }, 'Questions (' + e.questions.length + ')'), addBtn),
    e.status !== 'DRAFT' ? h('p', { class: 'muted small' }, 'This is the copy that was published; later edits in the bank do not change it.') : null,
    e.questions.length ? e.questions.map((q, i) => h('div', { class: 'result-item' },
      h('div', { class: 'qhead' }, h('strong', null, 'Q' + (i + 1)), classBadge(q), badge(q.typeLabel), badge(num(q.points) + ' pt'), h('span', null, q.id),
        q.classId && !e.assignedClassIds.includes(q.classId) && e.status === 'DRAFT' ? badge('Assign ' + q.className + ' before publishing', 'warn') : null,
        editable ? h('button', { class: 'sm danger', style: { marginLeft: 'auto' }, onclick: act(async () => {
          await api('DELETE', '/api/exams/' + e.id + '/questions/' + q.id); reload();
        }, 'Question removed.') }, 'Remove') : null),
      h('div', { style: { whiteSpace: 'pre-wrap' } }, q.text),
      q.choices ? h('ol', { type: 'A', class: 'small' }, q.choices.map(c => h('li', null, c))) : null,
      h('div', { class: 'small muted', style: { marginTop: '4px' } }, 'Key: ' + q.answerKey))) : h('div', { class: 'empty' }, 'No questions yet.')));
}

async function examAssignment(body, e, owner, reload) {
  const editable = owner && (e.status === 'DRAFT' || e.status === 'PUBLISHED');
  const classes = await api('GET', '/api/classes');
  const toggle = async (kind, targetId, assigned) => {
    try { await api('POST', '/api/exams/' + e.id + '/assign', { kind, targetId, assigned }); reload(); }
    catch (err) { toast(err.message, true); reload(); }
  };
  const whole = checkbox('Assign to every student enrolled in ' + e.subjectCode + ' (' + e.enrolled.length + ')', e.assignWholeSubject, { disabled: !editable });
  whole.box.addEventListener('change', () => toggle('subject', null, whole.box.checked));
  const cls = new Set(e.assignedClassIds), stu = new Set(e.assignedStudentIds);
  add(body, [h('div', { class: 'info-box' }, 'Currently assigned to ' + e.assignment + ' — ' + e.assignedTotal + ' student(s) in total.'),
    h('div', { class: 'grid cols-2' },
      h('div', { class: 'card' }, h('h2', null, 'Whole subject'), whole, h('hr'), h('h2', null, 'Classes'),
        classes.length ? classes.map(c => { const cb = checkbox(c.name + ' (' + c.size + ')', cls.has(c.id), { disabled: !editable }); cb.box.addEventListener('change', () => toggle('class', c.id, cb.box.checked)); return cb; })
          : h('p', { class: 'muted' }, 'No classes exist.')),
      h('div', { class: 'card' }, h('h2', null, 'Individual students'),
        h('p', { class: 'muted small' }, 'Only students enrolled in ' + e.subjectCode + ' can be assigned.'),
        e.enrolled.length ? e.enrolled.map(x => { const cb = checkbox(x.fullName + ' (' + x.studentNumber + ')', stu.has(x.id), { disabled: !editable }); cb.box.addEventListener('change', () => toggle('student', x.id, cb.box.checked)); return cb; })
          : h('p', { class: 'muted' }, 'Nobody is enrolled in this subject yet.')))]);
}

async function examResults(body, e, owner) {
  const d = await api('GET', '/api/exams/' + e.id + '/results');
  add(body, [owner ? h('div', { class: 'row', style: { marginBottom: '12px' } }, h('button', { class: 'primary', onclick: act(async () => {
    const r = await api('POST', '/api/exams/' + e.id + '/release');
    toast(r.released + ' result(s) released' + (r.pending ? ', ' + r.pending + ' still need manual grading.' : '.')); render();
  }) }, 'Release all final results')) : null,
  h('div', { class: 'card' }, h('h2', null, 'Ranking'), table(['#', 'Student', 'Attempt', { t: 'Score', cls: 'num' }, { t: '%', cls: 'num' }, 'Result', 'Status', ''],
    d.ranking.map((r, i) => h('tr', { class: 'clickable', onclick: () => go('#/result/' + r.id) },
      h('td', null, i + 1), h('td', null, r.studentName, h('div', { class: 'muted small' }, r.studentNumber)), h('td', null, r.attempt),
      h('td', { class: 'num' }, num(r.score) + ' / ' + num(r.maxScore)), h('td', { class: 'num' }, pct(r.percentage)),
      h('td', null, r.final ? (r.passed ? badge('Passed', 'ok') : badge('Failed', 'danger')) : badge('Pending', 'warn')),
      h('td', null, r.released ? badge('Released', 'primary') : badge(r.final ? 'Not released' : r.pendingCount + ' to grade', r.final ? '' : 'warn')),
      h('td', { class: 'right' }, h('button', { class: 'sm' }, owner && !r.final ? 'Grade' : 'Open')))), 'No submitted attempts yet.')),
  h('div', { class: 'card' }, h('h2', null, 'All attempts'), table(['Student', 'Attempt', 'State', 'Started', 'Submitted', { t: 'Answered', cls: 'num' }],
    d.attempts.map(a => h('tr', null, h('td', null, a.studentName), h('td', null, a.attempt), h('td', null, a.stateLabel + (a.submissionType ? ' (' + a.submissionType.toLowerCase() + ')' : '')),
      h('td', { class: 'small' }, a.startedAt), h('td', { class: 'small' }, a.submittedAt || '-'), h('td', { class: 'num' }, a.answered))), 'Nobody has started this exam yet.'))]);
}

async function examStats(body, e) {
  const s = await api('GET', '/api/exams/' + e.id + '/stats');
  const stat = (v, l) => h('div', { class: 'stat' }, h('div', { class: 'v' }, v), h('div', { class: 'l' }, l));
  add(body, [h('div', { class: 'stats' },
    stat(pct(s.average), 'Average (best attempt)'), stat(pct(s.highest), 'Highest' + (s.highestStudent ? ' · ' + s.highestStudent : '')),
    stat(pct(s.lowest), 'Lowest' + (s.lowestStudent ? ' · ' + s.lowestStudent : '')), stat(pct(s.passRate), 'Pass rate (' + s.passed + '/' + (s.passed + s.failed) + ')'),
    stat(s.studentsSubmitted + ' / ' + s.assigned, 'Students submitted'), stat(s.notStarted, 'Not started'),
    stat(s.attemptsInProgress, 'In progress'), stat(s.pendingResults, 'Awaiting grading')),
  h('div', { class: 'card' }, h('h2', null, 'Per-question average'), table(['Question', { t: 'Responses', cls: 'num' }, 'Average'],
    s.questions.map(q => h('tr', null, h('td', null, q.label), h('td', { class: 'num' }, q.responses),
      h('td', { style: { minWidth: '160px' } }, h('div', { class: 'row' }, h('div', { class: 'bar grow' }, h('span', { style: { width: Math.max(0, Math.min(100, q.average)) + '%' } })), h('span', { class: 'small nowrap' }, pct(q.average)))))), 'No final results yet.')),
  h('div', { class: 'row' }, h('button', { onclick: () => {
    const blob = new Blob([s.text], { type: 'text/plain' });
    const a = h('a', { href: URL.createObjectURL(blob), download: (s.title || 'exam').replace(/[^\w\-]+/g, '_') + '_statistics.txt' });
    document.body.appendChild(a); a.click(); a.remove();
  } }, 'Download as .txt'))]);
}

// =================================================================== grading

route('#/grading', async (main) => {
  const rows = await api('GET', '/api/grading/pending');
  add(main, [pageHead('Grading'), h('div', { class: 'card' }, h('h2', null, 'Awaiting manual grading'),
    table(['Exam', 'Student', 'Attempt', { t: 'To grade', cls: 'num' }, { t: 'Score so far', cls: 'num' }, ''], rows.map(r => h('tr', { class: 'clickable', onclick: () => go('#/result/' + r.id) },
      h('td', null, r.examTitle), h('td', null, r.studentName), h('td', null, r.attempt), h('td', { class: 'num' }, r.pendingCount),
      h('td', { class: 'num' }, num(r.score) + ' / ' + num(r.maxScore)), h('td', { class: 'right' }, h('button', { class: 'sm primary' }, 'Grade')))),
    'Nothing to grade. Essays and manually graded enumerations appear here after students submit.'))]);
});

route('#/result/:id', async (main, p) => {
  const r = await api('GET', '/api/results/' + p.id);
  const canGrade = r.owner && !r.released;
  const fb = textarea(r.overallFeedback, { rows: 3, disabled: !r.owner });
  const actions = [];
  if (r.owner) {
    if (r.released) actions.push(h('button', { onclick: act(async () => { await api('POST', '/api/results/' + r.id + '/release', { release: false }); render(); }, 'Release withdrawn.') }, 'Withdraw release'));
    else actions.push(h('button', { class: 'primary', disabled: !r.final, title: r.final ? null : 'Grade every pending answer first', onclick: act(async () => { await api('POST', '/api/results/' + r.id + '/release', { release: true }); render(); }, 'Result released to the student.') }, 'Release to student'));
  }
  add(main, [pageHead(r.studentName + ' · ' + r.examTitle, actions, ['#/exam/' + r.examId, 'Exam']),
    h('div', { class: 'stats' },
      h('div', { class: 'stat' }, h('div', { class: 'v' }, num(r.score) + ' / ' + num(r.maxScore)), h('div', { class: 'l' }, 'Score')),
      h('div', { class: 'stat' }, h('div', { class: 'v' }, pct(r.percentage)), h('div', { class: 'l' }, r.final ? (r.passed ? 'Passed' : 'Failed') : 'Provisional')),
      h('div', { class: 'stat' }, h('div', { class: 'v' }, r.pendingCount), h('div', { class: 'l' }, 'Answers to grade')),
      h('div', { class: 'stat' }, h('div', { class: 'v', style: { fontSize: '1rem' } }, r.released ? 'Released' : 'Not released'), h('div', { class: 'l' }, 'Attempt ' + r.attempt + ' · ' + r.submissionType + ' · ' + r.submittedAt))),
    r.released && r.owner ? h('div', { class: 'info-box' }, 'Withdraw the release to change scores.') : null,
    h('div', { class: 'card' }, r.items.map(it => {
      const pts = input(it.earned, { type: 'number', min: 0, max: it.max, step: 'any', style: { width: '110px' } });
      const f = textarea(it.feedback, { rows: 2, placeholder: 'Feedback for the student (optional)' });
      return h('div', { class: 'result-item' },
        h('div', { class: 'qhead' }, h('strong', null, 'Q' + it.number), badge(it.typeLabel), it.pending ? badge('Needs grading', 'warn') : badge(num(it.earned) + ' / ' + num(it.max) + ' pt', it.earned >= it.max ? 'ok' : (it.earned > 0 ? 'warn' : 'danger'))),
        h('div', { style: { whiteSpace: 'pre-wrap', marginBottom: '8px' } }, it.text),
        h('dl', { class: 'kv' }, h('dt', null, 'Student answer'), h('dd', null, it.answer), h('dt', null, 'Answer key'), h('dd', null, it.answerKey),
          it.rubric ? [h('dt', null, 'Rubric'), h('dd', null, it.rubric)] : null, !canGrade && it.feedback ? [h('dt', null, 'Feedback'), h('dd', null, it.feedback)] : null),
        canGrade ? h('div', { class: 'row', style: { marginTop: '10px', alignItems: 'flex-start' } },
          h('div', null, h('label', null, 'Points (max ' + num(it.max) + ')'), pts), h('div', { class: 'grow' }, h('label', null, 'Feedback'), f),
          h('button', { class: it.pending ? 'primary' : '', style: { marginTop: '22px' }, onclick: act(async () => {
            await api('POST', '/api/results/' + r.id + '/grade', { questionId: it.questionId, points: pts.value, feedback: f.value }); render();
          }, 'Grade saved.') }, it.pending ? 'Save grade' : 'Regrade')) : null);
    })),
    h('div', { class: 'card' }, h('h2', null, 'Overall feedback'), fb, r.owner ? h('div', { class: 'row', style: { marginTop: '10px' } },
      h('button', { onclick: act(async () => { await api('POST', '/api/results/' + r.id + '/feedback', { feedback: fb.value }); }, 'Feedback saved.') }, 'Save feedback')) : null)]);
});

// =================================================================== reports

route('#/reports', async (main) => {
  const [subjects, classes, exams] = await Promise.all([api('GET', '/api/subjects'), api('GET', '/api/classes'), api('GET', '/api/exams')]);
  const fs = select([['', 'All subjects']].concat(subjects.map(s => [s.id, s.code])), '');
  const fc = select([['', 'All classes']].concat(classes.map(c => [c.id, c.name])), '');
  const fe = select([['', 'All exams']].concat(exams.map(e => [e.id, e.title])), '');
  const out = h('div');
  const pc = select([['', 'Choose a class…']].concat(classes.map(c => [c.id, c.name])), '');
  const perf = h('div');
  async function load() {
    const rows = await api('GET', '/api/reports/results?subjectId=' + fs.value + '&classId=' + fc.value + '&examId=' + fe.value);
    clear(out).appendChild(table(['Student', 'Exam', 'Subject', 'Attempt', { t: 'Score', cls: 'num' }, { t: '%', cls: 'num' }, 'Result'], rows.map(r => h('tr', { class: 'clickable', onclick: () => go('#/result/' + r.id) },
      h('td', null, r.studentName, h('div', { class: 'muted small' }, r.studentNumber)), h('td', null, r.examTitle), h('td', null, r.subjectCode), h('td', null, r.attempt),
      h('td', { class: 'num' }, num(r.score) + ' / ' + num(r.maxScore)), h('td', { class: 'num' }, pct(r.percentage)),
      h('td', null, r.final ? (r.passed ? badge('Passed', 'ok') : badge('Failed', 'danger')) : badge('Pending', 'warn')))), 'No results match these filters.'));
  }
  pc.addEventListener('change', act(async () => {
    clear(perf);
    if (!pc.value) return;
    const rows = await api('GET', '/api/reports/class/' + pc.value);
    perf.appendChild(table(['Student', { t: 'Results', cls: 'num' }, { t: 'Average', cls: 'num' }, { t: 'Best', cls: 'num' }, { t: 'Lowest', cls: 'num' }, { t: 'Passed', cls: 'num' }],
      rows.map(x => h('tr', null, h('td', null, x.name, h('div', { class: 'muted small' }, x.studentNumber)), h('td', { class: 'num' }, x.results),
        h('td', { class: 'num' }, x.results ? pct(x.average) : '-'), h('td', { class: 'num' }, x.results ? pct(x.best) : '-'), h('td', { class: 'num' }, x.results ? pct(x.lowest) : '-'), h('td', { class: 'num' }, x.passed))), 'This class has no members.'));
  }));
  [fs, fc, fe].forEach(x => x.addEventListener('change', act(load)));
  add(main, [pageHead('Reports'),
    h('div', { class: 'card' }, h('h2', null, 'Results'), h('div', { class: 'toolbar' }, fs, fc, fe), out),
    h('div', { class: 'card' }, h('h2', null, 'Class performance'), h('div', { class: 'toolbar' }, pc), perf)]);
  await load();
});

// =================================================================== teacher dashboard

route('#/teacher', async (main) => {
  const [subjects, exams, pending] = await Promise.all([api('GET', '/api/subjects'), api('GET', '/api/exams'), api('GET', '/api/grading/pending')]);
  const count = (st) => exams.filter(e => e.effectiveStatus === st).length;
  const stat = (v, l, href) => h('a', { class: 'stat', href, style: { textDecoration: 'none', color: 'inherit' } }, h('div', { class: 'v' }, v), h('div', { class: 'l' }, l));
  add(main, [pageHead('Welcome, ' + S.user.fullName, [h('button', { class: 'primary', onclick: newExam }, '+ New exam')]),
    h('div', { class: 'stats' }, stat(subjects.length, 'Subjects', '#/subjects'), stat(count('DRAFT'), 'Draft exams', '#/exams'),
      stat(count('ACTIVE') + count('PUBLISHED'), 'Published exams', '#/exams'), stat(pending.length, 'Results to grade', '#/grading')),
    h('div', { class: 'grid cols-2' },
      h('div', { class: 'card' }, h('h2', null, 'Recent exams'), exams.length ? exams.slice(-6).reverse().map(e => h('div', { class: 'row', style: { padding: '6px 0', borderTop: '1px solid var(--border)' } },
        h('a', { class: 'grow', href: '#/exam/' + e.id }, e.title), statusBadge(e.effectiveStatus, e.statusLabel))) : h('p', { class: 'muted' }, 'No exams yet.')),
      h('div', { class: 'card' }, h('h2', null, 'Needs grading'), pending.length ? pending.slice(0, 6).map(r => h('div', { class: 'row', style: { padding: '6px 0', borderTop: '1px solid var(--border)' } },
        h('a', { class: 'grow', href: '#/result/' + r.id }, r.studentName + ' · ' + r.examTitle), badge(r.pendingCount + ' answer(s)', 'warn'))) : h('p', { class: 'muted' }, 'All caught up.')))]);
});

// =================================================================== student: exams

route('#/student', async (main) => {
  const exams = await api('GET', '/api/student/exams');
  add(main, [pageHead('My exams'), exams.length ? h('div', { class: 'grid cols-2' }, exams.map(e => {
    const open = e.effectiveStatus === 'ACTIVE';
    const left = e.maxAttempts - e.attemptsUsed;
    let btn;
    if (e.openSessionId) btn = h('button', { class: 'primary', onclick: () => go('#/take/' + e.openSessionId) }, 'Resume exam');
    else if (open && left > 0) btn = h('button', { class: 'primary', onclick: () => startExam(e) }, e.attemptsUsed ? 'Start another attempt' : 'Start exam');
    else btn = h('button', { disabled: true }, e.effectiveStatus === 'PUBLISHED' ? 'Opens ' + e.opensLabel : (left <= 0 ? 'No attempts left' : 'Closed'));
    return h('div', { class: 'card' },
      h('div', { class: 'row' }, h('h2', { class: 'grow', style: { margin: 0 } }, e.title), statusBadge(e.effectiveStatus, open ? 'Open' : (e.effectiveStatus === 'PUBLISHED' ? 'Upcoming' : 'Closed'))),
      h('p', { class: 'muted small', style: { margin: '4px 0 10px' } }, e.subjectCode + ' · ' + e.teacherName),
      h('dl', { class: 'kv' }, h('dt', null, 'Questions'), h('dd', null, e.questionCount + ' (' + num(e.totalPoints) + ' points)'),
        h('dt', null, 'Time limit'), h('dd', null, e.timerLabel), h('dt', null, 'Attempts'), h('dd', null, e.attemptsUsed + ' of ' + e.maxAttempts + ' used'),
        e.opensAt ? [h('dt', null, 'Opens'), h('dd', null, e.opensLabel)] : null, e.closesAt ? [h('dt', null, 'Closes'), h('dd', null, e.closesLabel)] : null),
      h('div', { class: 'row', style: { marginTop: '14px' } }, btn));
  })) : h('div', { class: 'card empty' }, 'No exams have been assigned to you yet.')]);
});

function startExam(e) {
  modal('Start “' + e.title + '”?', h('div', null,
    e.instructions ? h('div', { class: 'card', style: { boxShadow: 'none', whiteSpace: 'pre-wrap' } }, e.instructions) : null,
    h('dl', { class: 'kv' }, h('dt', null, 'Questions'), h('dd', null, String(e.questionCount)), h('dt', null, 'Time limit'), h('dd', null, e.timerLabel),
      h('dt', null, 'Passing score'), h('dd', null, pct(e.passingPercentage)), h('dt', null, 'Attempt'), h('dd', null, (e.attemptsUsed + 1) + ' of ' + e.maxAttempts)),
    h('p', { class: 'muted small', style: { marginTop: '12px' } }, 'The timer starts as soon as you begin and keeps running if you close the page. Answers are saved automatically.')), {
    okText: 'Begin exam',
    onOk: async () => { const s = await api('POST', '/api/student/exams/' + e.id + '/start'); go('#/take/' + s.sessionId); }
  });
}

// =================================================================== student: taking an exam

route('#/take/:id', async (root, p) => {
  let s = await api('GET', '/api/student/sessions/' + p.id);
  let idx = 0, deadline = null, warned = {}, refreshing = false;
  const saveState = h('span', { class: 'save-state' });
  const timerEl = h('span', { class: 'timer', role: 'timer', 'aria-live': 'off' });
  const navEl = h('div', { class: 'qnav' });
  const panel = h('div', { class: 'card' });
  const progress = h('div', { class: 'small muted' });
  const queue = {};

  function setDeadline() { deadline = s.remainingMs === null || s.remainingMs === undefined ? null : Date.now() + s.remainingMs; }

  function ended() {
    stopTimers();
    clear(root).appendChild(h('div', { class: 'login-wrap' }, h('div', { class: 'card', style: { maxWidth: '520px', textAlign: 'center' } },
      h('h1', null, s.state === 'SUBMITTED' ? 'Exam submitted' : 'Attempt ended'), h('p', null, s.ending || ''),
      h('p', { class: 'muted' }, 'Your score appears under “My results” once your teacher releases it.'),
      h('div', { class: 'row', style: { justifyContent: 'center' } }, h('a', { class: 'btn', href: '#/my-results' }, 'My results'), h('a', { class: 'btn', href: '#/student' }, 'My exams')))));
  }

  async function refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
      s = await api('GET', '/api/student/sessions/' + p.id);
      if (!s.open) return ended();
      setDeadline(); draw();
    } catch (e) { toast(e.message, true); }
    finally { refreshing = false; }
  }

  function tick() {
    if (deadline === null) { timerEl.textContent = 'No time limit'; return; }
    if (s.state === 'PAUSED') { timerEl.textContent = 'Paused · ' + fmtTime(s.remainingMs); return; }
    const ms = Math.max(0, deadline - Date.now());
    timerEl.textContent = fmtTime(ms);
    timerEl.className = 'timer' + (ms <= 60000 ? ' critical' : ms <= 300000 ? ' low' : '');
    for (const m of [10, 5, 1]) {
      if (ms <= m * 60000 && ms > (m * 60000 - 5000) && !warned[m]) { warned[m] = true; toast(m + ' minute' + (m > 1 ? 's' : '') + ' left.', m === 1); }
    }
    if (ms <= 0) refresh();
  }
  function fmtTime(ms) {
    const t = Math.ceil(ms / 1000), hh = Math.floor(t / 3600), mm = Math.floor(t % 3600 / 60), ss = t % 60;
    return (hh ? hh + ':' : '') + String(mm).padStart(2, '0') + ':' + String(ss).padStart(2, '0');
  }

  function save(q, values) {
    saveState.textContent = 'Saving…';
    const run = () => api('POST', '/api/student/sessions/' + p.id + '/answer', { questionId: q.id, values, index: idx }).then(r => {
      q.answer = r.answer; q.answered = r.answered;
      if (r.remainingMs !== null && r.remainingMs !== undefined && s.state !== 'PAUSED') deadline = Date.now() + r.remainingMs;
      saveState.textContent = 'Saved ' + new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
      drawNav();
    }).catch(e => {
      saveState.textContent = 'Not saved';
      toast(e.message, true);
      if (/expired|submitted|paused|closed/i.test(e.message)) refresh();
    });
    queue[q.id] = (queue[q.id] || Promise.resolve()).then(run);
    return queue[q.id];
  }
  const debounced = {};
  function saveLater(q, getValues) {
    saveState.textContent = 'Typing…';
    clearTimeout(debounced[q.id]);
    debounced[q.id] = setTimeout(() => save(q, getValues()), 700);
  }
  function flush(q, getValues) { if (debounced[q.id]) { clearTimeout(debounced[q.id]); debounced[q.id] = null; return save(q, getValues()); } return Promise.resolve(); }

  let flushCurrent = () => Promise.resolve();

  function answerInput(q) {
    const letters = (n) => Array.from({ length: n }, (_, i) => String.fromCharCode(65 + i));
    const cur = q.answer || [];
    if (q.type === 'MULTIPLE_CHOICE' || q.type === 'MULTIPLE_SELECT' || q.type === 'TRUE_FALSE') {
      const multi = q.type === 'MULTIPLE_SELECT';
      const opts = q.type === 'TRUE_FALSE' ? [['true', '', 'True'], ['false', '', 'False']] : q.choices.map((c, i) => [letters(q.choices.length)[i], letters(q.choices.length)[i], c]);
      const box = h('div', { class: 'options', role: multi ? 'group' : 'radiogroup' });
      const inputs = [];
      opts.forEach(([v, l, text]) => {
        const inp = h('input', { type: multi ? 'checkbox' : 'radio', name: 'q_' + q.id, value: v, checked: cur.includes(v) });
        const lab = h('label', { class: 'option' + (cur.includes(v) ? ' selected' : '') }, inp, l ? h('span', { class: 'letter' }, l + '.') : null, h('span', null, text));
        inp.addEventListener('change', () => {
          inputs.forEach(x => x.parentNode.classList.toggle('selected', x.checked));
          save(q, inputs.filter(x => x.checked).map(x => x.value));
        });
        inputs.push(inp); box.appendChild(lab);
      });
      return box;
    }
    if (q.type === 'IDENTIFICATION' || q.type === 'NUMERICAL') {
      const inp = input(cur[0] || '', { inputmode: q.type === 'NUMERICAL' ? 'decimal' : null, placeholder: q.type === 'NUMERICAL' ? 'Type a number' : 'Your answer', 'aria-label': 'Answer' });
      const get = () => [inp.value];
      inp.addEventListener('input', () => saveLater(q, get));
      inp.addEventListener('blur', () => flush(q, get));
      flushCurrent = () => flush(q, get);
      return inp;
    }
    if (q.type === 'ESSAY') {
      const ta = textarea(cur[0] || '', { rows: 12, 'aria-label': 'Essay answer' });
      const wc = h('div', { class: 'small muted' });
      const count = () => { const n = ta.value.trim() ? ta.value.trim().split(/\s+/).length : 0; wc.textContent = n + ' word' + (n === 1 ? '' : 's') + (q.minWords ? ' · at least ' + q.minWords + ' recommended' : ''); };
      const get = () => [ta.value];
      ta.addEventListener('input', () => { count(); saveLater(q, get); });
      ta.addEventListener('blur', () => flush(q, get));
      flushCurrent = () => flush(q, get);
      count();
      return h('div', null, ta, wc);
    }
    if (q.type === 'FILL_IN_THE_BLANKS') {
      const parts = q.text.split(/_{3,}/);
      const inputs = [];
      const get = () => inputs.map(i => i.value);
      const el = h('div', { class: 'blank-text' });
      parts.forEach((part, i) => {
        el.appendChild(document.createTextNode(part));
        if (i < parts.length - 1) {
          const inp = input(cur[i] || '', { 'aria-label': 'Blank ' + (i + 1), placeholder: '(' + (i + 1) + ')' });
          inp.addEventListener('input', () => saveLater(q, get));
          inp.addEventListener('blur', () => flush(q, get));
          inputs.push(inp); el.appendChild(inp);
        }
      });
      flushCurrent = () => flush(q, get);
      return el;
    }
    if (q.type === 'ENUMERATION') {
      const inputs = [];
      const get = () => inputs.map(i => i.value);
      const el = h('div', { class: 'list-editor' }, q.orderMatters ? h('p', { class: 'small muted' }, 'Order matters.') : null);
      for (let i = 0; i < q.itemCount; i++) {
        const inp = input(cur[i] || '', { placeholder: 'Item ' + (i + 1), 'aria-label': 'Item ' + (i + 1) });
        inp.addEventListener('input', () => saveLater(q, get));
        inp.addEventListener('blur', () => flush(q, get));
        inputs.push(inp); el.appendChild(h('div', { class: 'item' }, h('span', { class: 'muted', style: { width: '1.6em' } }, (i + 1) + '.'), inp));
      }
      flushCurrent = () => flush(q, get);
      return el;
    }
    if (q.type === 'MATCHING') {
      const L = letters(q.right.length);
      const sels = [];
      const el = h('div', null,
        h('div', { class: 'card', style: { boxShadow: 'none', background: 'var(--surface-2)' } }, h('strong', null, 'Column B'),
          h('div', null, q.right.map((r, i) => h('div', null, h('strong', null, L[i] + '. '), r)))));
      q.left.forEach((l, i) => {
        const sel = select([['', '— choose —']].concat(q.right.map((r, j) => [L[j], L[j] + '. ' + truncate(r, 60)])), cur[i] || '', { 'aria-label': 'Match for ' + l });
        sel.addEventListener('change', () => save(q, sels.map(x => x.value)));
        sels.push(sel);
        el.appendChild(h('div', { class: 'match-row' }, h('div', null, h('strong', null, (i + 1) + '. '), l), sel));
      });
      return el;
    }
    return h('p', null, 'Unsupported question type.');
  }

  function drawNav() {
    clear(navEl);
    s.questions.forEach((q, i) => navEl.appendChild(h('button', { class: [q.answered ? 'answered' : '', q.flagged ? 'flagged' : '', i === idx ? 'current' : ''].join(' '),
      'aria-label': 'Question ' + (i + 1) + (q.answered ? ', answered' : '') + (q.flagged ? ', flagged' : ''), onclick: () => goTo(i) }, i + 1)));
    const answered = s.questions.filter(q => q.answered).length;
    progress.textContent = answered + ' of ' + s.questions.length + ' answered';
  }

  async function goTo(i) {
    await flushCurrent();
    idx = Math.max(0, Math.min(s.questions.length - 1, i));
    api('POST', '/api/student/sessions/' + p.id + '/position', { index: idx }).catch(() => {});
    draw();
  }

  function draw() {
    drawNav();
    clear(panel);
    flushCurrent = () => Promise.resolve();
    if (s.state === 'PAUSED') {
      panel.appendChild(h('div', { class: 'paused-cover' }, h('h2', null, 'Exam paused'), h('p', { class: 'muted' }, 'The timer is stopped. Questions are hidden while paused.'),
        h('button', { class: 'primary', onclick: act(async () => { s = await api('POST', '/api/student/sessions/' + p.id + '/resume'); if (!s.open) return ended(); setDeadline(); draw(); }) }, 'Resume')));
      return;
    }
    const q = s.questions[idx];
    const flag = checkbox('Flag for review', q.flagged);
    flag.box.addEventListener('change', act(async () => {
      await api('POST', '/api/student/sessions/' + p.id + '/flag', { questionId: q.id, flagged: flag.box.checked });
      q.flagged = flag.box.checked; drawNav();
    }));
    add(panel, [
      h('div', { class: 'qhead' }, h('strong', null, 'Question ' + (idx + 1) + ' of ' + s.questions.length), badge(q.typeLabel), badge(num(q.points) + ' pt' + (q.points === 1 ? '' : 's')), h('span', { style: { marginLeft: 'auto' } }, flag)),
      q.type === 'FILL_IN_THE_BLANKS' ? null : h('div', { class: 'qtext' }, q.text),
      h('p', { class: 'small muted' }, q.hint),
      answerInput(q),
      h('div', { class: 'qfoot' },
        h('button', { disabled: idx === 0, onclick: () => goTo(idx - 1) }, '← Previous'),
        h('button', { disabled: idx === s.questions.length - 1, onclick: () => goTo(idx + 1) }, 'Next →'),
        h('button', { class: 'ghost', onclick: act(async () => { await save(q, []); draw(); }) }, 'Clear answer'),
        h('span', { class: 'grow' }), saveState)]);
  }

  async function submit() {
    await flushCurrent();
    const unanswered = s.questions.filter(q => !q.answered).length;
    const flagged = s.questions.filter(q => q.flagged).length;
    const msg = h('div', null, h('p', null, 'You answered ' + (s.questions.length - unanswered) + ' of ' + s.questions.length + ' questions.'),
      unanswered ? h('div', { class: 'error-box' }, unanswered + ' question(s) are unanswered and will score 0.') : null,
      flagged ? h('div', { class: 'info-box' }, flagged + ' question(s) are flagged for review.') : null,
      h('p', { class: 'muted small' }, 'You cannot change your answers after submitting.'));
    modal('Submit exam?', msg, { okText: 'Submit now', onOk: async () => {
      await api('POST', '/api/student/sessions/' + p.id + '/submit');
      await refresh();
    } });
  }

  if (!s.open) return ended();
  idx = Math.min(s.currentIndex || 0, s.questions.length - 1);
  setDeadline();
  clear(root);
  add(root, [
    h('div', { class: 'exam-top' }, h('div', null, h('div', { class: 'title' }, s.examTitle), h('div', { class: 'small muted' }, s.subjectCode + ' · attempt ' + s.attempt + ' of ' + s.maxAttempts)),
      timerEl,
      s.allowPause ? h('button', { onclick: act(async () => {
        await flushCurrent();
        if (s.state === 'PAUSED') return;
        s = await api('POST', '/api/student/sessions/' + p.id + '/pause'); setDeadline(); draw(); tick();
      }) }, 'Pause') : null,
      h('button', { class: 'primary', onclick: submit }, 'Submit')),
    h('div', { class: 'exam-body' }, panel,
      h('aside', null, h('div', { class: 'card' }, h('h3', null, 'Questions'), progress, h('div', { style: { height: '10px' } }), navEl,
        h('div', { class: 'legend' }, h('span', null, '■ answered'), h('span', null, '● flagged')),
        h('hr'), h('a', { href: '#/student', class: 'small' }, 'Leave (your time keeps running)'),
        s.instructions ? h('details', { style: { marginTop: '10px' } }, h('summary', { class: 'small' }, 'Instructions'), h('p', { class: 'small', style: { whiteSpace: 'pre-wrap' } }, s.instructions)) : null)))]);
  draw();
  tick();
  S.timers.push(setInterval(tick, 500));
  S.timers.push(setInterval(refreshQuiet, 20000));
  async function refreshQuiet() {
    try {
      const fresh = await api('GET', '/api/student/sessions/' + p.id);
      if (!fresh.open) { s = fresh; return ended(); }
      s.remainingMs = fresh.remainingMs; s.state = fresh.state; setDeadline();
    } catch (e) { /* temporary network problem: keep counting locally */ }
  }
}, { bare: true });

// =================================================================== student: results

route('#/my-results', async (main) => {
  const rows = await api('GET', '/api/student/results');
  add(main, [pageHead('My results'), h('div', { class: 'card' }, table(['Exam', 'Attempt', 'Submitted', { t: 'Score', cls: 'num' }, { t: '%', cls: 'num' }, 'Result'], rows.map(r => {
    const status = r.visible ? (r.passed ? badge('Passed', 'ok') : badge('Failed', 'danger'))
      : r.state === 'IN_PROGRESS' || r.state === 'PAUSED' ? badge('In progress', 'primary')
      : r.state === 'EXPIRED' ? badge('Expired', 'danger')
      : badge(r.final ? 'Not released yet' : 'Awaiting grading', 'warn');
    return h('tr', { class: r.visible ? 'clickable' : null, onclick: r.visible ? () => go('#/my-result/' + r.resultId) : (r.state === 'IN_PROGRESS' || r.state === 'PAUSED' ? () => go('#/take/' + r.sessionId) : null) },
      h('td', null, r.examTitle), h('td', null, r.attempt), h('td', { class: 'small' }, r.submittedAt ? r.submittedAt + (r.submissionType ? ' (' + r.submissionType.toLowerCase() + ')' : '') : '-'),
      h('td', { class: 'num' }, r.visible ? num(r.score) + ' / ' + num(r.maxScore) : '-'), h('td', { class: 'num' }, r.visible ? pct(r.percentage) : '-'), h('td', null, status));
  }), 'You have not taken any exams yet.'))]);
});

route('#/my-result/:id', async (main, p) => {
  const r = await api('GET', '/api/student/results/' + p.id);
  add(main, [pageHead(r.examTitle, null, ['#/my-results', 'My results']),
    h('div', { class: 'stats' },
      h('div', { class: 'stat' }, h('div', { class: 'v' }, num(r.score) + ' / ' + num(r.maxScore)), h('div', { class: 'l' }, 'Score')),
      h('div', { class: 'stat' }, h('div', { class: 'v' }, pct(r.percentage)), h('div', { class: 'l' }, 'Percentage')),
      h('div', { class: 'stat' }, h('div', { class: 'v' }, r.passed ? 'Passed' : 'Failed'), h('div', { class: 'l' }, 'Attempt ' + r.attempt))),
    r.overallFeedback ? h('div', { class: 'card' }, h('h2', null, 'Teacher feedback'), h('p', { style: { whiteSpace: 'pre-wrap' } }, r.overallFeedback)) : null,
    h('div', { class: 'card' }, r.items.map(it => h('div', { class: 'result-item' },
      h('div', { class: 'qhead' }, h('strong', null, 'Q' + it.number), badge(it.typeLabel), badge(num(it.earned) + ' / ' + num(it.max) + ' pt', it.earned >= it.max ? 'ok' : (it.earned > 0 ? 'warn' : 'danger'))),
      h('div', { style: { whiteSpace: 'pre-wrap' } }, it.text),
      h('dl', { class: 'kv' }, h('dt', null, 'Your answer'), h('dd', null, it.answer),
        it.feedback ? [h('dt', null, 'Feedback'), h('dd', null, it.feedback)] : null,
        it.explanation ? [h('dt', null, 'Explanation'), h('dd', null, it.explanation)] : null))))]);
});

// =================================================================== start

window.addEventListener('hashchange', render);
(async function boot() {
  try {
    const st = await api('GET', '/api/status');
    S.user = st.user; S.setupNeeded = st.setupNeeded; S.zone = st.zone;
  } catch (e) {
    document.getElementById('app').textContent = e.message;
    return;
  }
  render();
})();
