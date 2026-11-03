#!/usr/bin/env python3
"""Binary linkage check of a mod jar against one loader install, from bytecode alone.

    python3 -I tools/test-compat/linkcheck.py --jar dist/<mod>.jar --api net/fabricmc/ \
        --cp <install dir or jar>... [--baseline <install dir or jar>...]

Every reference from the mod's own classes (net/loserver/oreradar/) to a class whose name starts
with an --api prefix must resolve in the --cp jars (jars nested in META-INF/jars/ included):
  - class references, including super classes and interfaces;
  - field and method references, looked up through the class hierarchy the way the JVM does;
  - lambdas and method references (invokedynamic): the functional interface and its method;
  - with --baseline (the install the jar was built and tested against): every method of the mod
    that overrides an --api method there must still override one in --cp, or it would silently
    stop being called.
Classes are never loaded, so client-only code is checked on a server install too. A member whose
lookup reaches a class outside the index (Minecraft's client classes, the JDK) and is not found
before it is counted as unverified, not as a failure.

Prints each problem and a summary; exit 1 if anything is unresolved.
"""
import argparse
import io
import os
import struct
import sys
import zipfile

OWN = 'net/loserver/oreradar/'
ACC_INTERFACE, ACC_STATIC, ACC_PRIVATE = 0x0200, 0x0008, 0x0002


class ClassInfo:
    __slots__ = ('name', 'access', 'super', 'interfaces', 'fields', 'methods', 'refs', 'indys')


def parse(data, want_refs=False):
    """Parses a class file into a ClassInfo (refs and invokedynamic sites only if want_refs)."""
    def u1():
        nonlocal off; v = data[off]; off += 1; return v

    def u2():
        nonlocal off; v = struct.unpack_from('>H', data, off)[0]; off += 2; return v

    def u4():
        nonlocal off; v = struct.unpack_from('>I', data, off)[0]; off += 4; return v

    off = 8
    count = u2()
    tags, vals = [0] * count, [None] * count
    i = 1
    while i < count:
        tag = u1(); tags[i] = tag
        if tag == 1:
            n = u2(); vals[i] = data[off:off + n].decode('utf-8', 'replace'); off += n
        elif tag in (3, 4):
            off += 4
        elif tag in (5, 6):
            off += 8; i += 1
        elif tag in (7, 8, 16, 19, 20):
            vals[i] = (u2(),)
        elif tag in (9, 10, 11, 12, 17, 18):
            vals[i] = (u2(), u2())
        elif tag == 15:
            vals[i] = (u1(), u2())
        else:
            raise ValueError('bad constant pool tag %d' % tag)
        i += 1
    utf = lambda k: vals[k]
    cls = lambda k: utf(vals[k][0])
    c = ClassInfo()
    c.access = u2(); c.name = cls(u2())
    s = u2(); c.super = cls(s) if s else None
    c.interfaces = [cls(u2()) for _ in range(u2())]

    def members():
        nonlocal off
        out = []
        for _ in range(u2()):
            acc, name, desc = u2(), utf(u2()), utf(u2())
            for _ in range(u2()):
                u2(); n = u4(); off += n
            out.append((acc, name, desc))
        return out

    c.fields, c.methods = members(), members()
    c.refs, c.indys = [], []
    if not want_refs:
        return c
    bootstrap = []
    for _ in range(u2()):
        name, length = utf(u2()), u4()
        if name == 'BootstrapMethods':
            save = off
            for _ in range(u2()):
                u2(); bootstrap.append([u2() for _ in range(u2())])
            off = save
        off += length
    for k in range(1, count):
        if tags[k] == 7:
            c.refs.append(('class', cls(k), '', ''))
        elif tags[k] in (9, 10, 11):
            nat = vals[vals[k][1]]
            kind = {9: 'field', 10: 'method', 11: 'imethod'}[tags[k]]
            c.refs.append((kind, cls(vals[k][0]), utf(nat[0]), utf(nat[1])))
        elif tags[k] == 18:
            bsm, nat = vals[k]
            name, desc = utf(vals[nat][0]), utf(vals[nat][1])
            args = bootstrap[bsm] if bsm < len(bootstrap) else []
            # LambdaMetafactory: arg 0 is the erased MethodType of the interface method.
            sam = utf(vals[args[0]][0]) if args and tags[args[0]] == 16 else None
            ret = desc[desc.rindex(')') + 1:]
            if sam and ret.startswith('L'):
                c.indys.append((ret[1:-1], name, sam))
    return c


def jars_in(path):
    if os.path.isdir(path):
        for root, _, files in os.walk(path):
            for f in sorted(files):
                if f.endswith('.jar'):
                    yield os.path.join(root, f)
    elif path.endswith('.jar'):
        yield path


def index_jar(zf, index, own=False):
    for n in zf.namelist():
        if n.endswith('.class') and not n.startswith('META-INF/versions/'):
            if own and not n.startswith(OWN):
                continue
            try:
                c = parse(zf.read(n), want_refs=own)
            except (ValueError, struct.error, IndexError):
                continue
            index.setdefault(c.name, c)  # first copy wins
        elif n.startswith('META-INF/jars/') and n.endswith('.jar') and not own:
            index_jar(zipfile.ZipFile(io.BytesIO(zf.read(n))), index)


def build_index(paths):
    index = {}
    for p in paths:
        for j in jars_in(p):
            try:
                index_jar(zipfile.ZipFile(j), index)
            except zipfile.BadZipFile:
                pass
    return index


def ancestors(index, name):
    """(classes found in the index, whether the walk hit a class outside it)."""
    out, seen, todo, missing = [], set(), [name], False
    while todo:
        n = todo.pop(0)
        if n in seen or n == 'java/lang/Object':
            continue
        seen.add(n)
        c = index.get(n)
        if c is None:
            missing = True
            continue
        out.append(c)
        if c.super:
            todo.append(c.super)
        todo.extend(c.interfaces)
    return out, missing


def resolve(index, kind, owner, name, desc):
    """'ok', 'unverified' or a reason it does not resolve."""
    o = owner.lstrip('[')
    if o.startswith('L'):
        o = o[1:-1]
    if owner.startswith('['):
        return 'ok' if (o in index or len(o) == 1) else 'class missing'
    c = index.get(o)
    if c is None:
        return 'class missing'
    if kind == 'class':
        return 'ok'
    is_iface = bool(c.access & ACC_INTERFACE)
    if kind == 'method' and is_iface:
        return 'is an interface here (IncompatibleClassChangeError)'
    if kind == 'imethod' and not is_iface:
        return 'is a class here (IncompatibleClassChangeError)'
    found, missing = ancestors(index, o)
    for a in found:
        members = a.fields if kind == 'field' else a.methods
        if any(n == name and d == desc for _, n, d in members):
            return 'ok'
        if name == '<init>':
            break  # constructors are never inherited
    if kind != 'field' and name in ('equals', 'hashCode', 'toString', 'getClass', 'clone', 'finalize'):
        return 'ok'
    if missing and name != '<init>':
        return 'unverified'
    return 'no such ' + ('field' if kind == 'field' else 'constructor' if name == '<init>' else 'method')


def api_methods(index, own, cls, api):
    """name+desc of every non-static, non-private method of an --api ancestor of cls."""
    out = set()
    found, _ = ancestors(index, cls.super) if cls.super else ([], False)
    for i in cls.interfaces:
        f, _ = ancestors(index, i)
        found += f
    for a in found:
        if a.name in own or not a.name.startswith(api):
            continue
        out.update((n, d) for acc, n, d in a.methods
                   if not acc & (ACC_STATIC | ACC_PRIVATE) and n != '<init>')
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--jar', required=True)
    ap.add_argument('--api', action='append', required=True)
    ap.add_argument('--cp', nargs='+', required=True)
    ap.add_argument('--baseline', nargs='+')
    a = ap.parse_args()
    api = tuple(a.api)

    own = {}
    index_jar(zipfile.ZipFile(a.jar), own, own=True)
    target = build_index(a.cp)
    for k, v in own.items():
        target.setdefault(k, v)  # the mod's own classes resolve against themselves

    refs = {}
    for c in own.values():
        for kind, owner, name, desc in c.refs:
            if owner.lstrip('[L').startswith(api):
                refs.setdefault((kind, owner, name, desc), set()).add(c.name)
        for iface, name, sam in c.indys:
            if iface.startswith(api):
                refs.setdefault(('class', iface, '', ''), set()).add(c.name)
                refs.setdefault(('imethod', iface, name, sam), set()).add(c.name)

    bad, unverified = 0, 0
    for (kind, owner, name, desc), users in sorted(refs.items()):
        r = resolve(target, kind, owner, name, desc)
        if r == 'ok':
            continue
        if r == 'unverified':
            unverified += 1
            continue
        bad += 1
        what = owner.replace('/', '.') + ('' if kind == 'class' else '.' + name + ('' if kind == 'field' else desc))
        print('UNRESOLVED %s %s: %s' % (kind, what, r))
        for u in sorted(users)[:5]:
            print('    used by ' + u)

    lost = 0
    if a.baseline:
        base = build_index(a.baseline)
        for k, v in own.items():
            base.setdefault(k, v)
        for c in own.values():
            mine = {(n, d) for acc, n, d in c.methods if not acc & (ACC_STATIC | ACC_PRIVATE) and n != '<init>'}
            then = mine & api_methods(base, own, c, api)
            now = api_methods(target, own, c, api)
            for n, d in sorted(then - now):
                lost += 1
                print('OVERRIDE LOST %s.%s%s: overrides an API method in the baseline, none here'
                      % (c.name.replace('/', '.'), n, d))

    print('%d API references checked (%d unverified: defined outside the index), %d unresolved%s'
          % (len(refs), unverified, bad, ', %d overrides lost' % lost if a.baseline else ''))
    sys.exit(1 if bad or lost else 0)


if __name__ == '__main__':
    main()
