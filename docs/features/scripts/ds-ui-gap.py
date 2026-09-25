#
# SPDX-FileCopyrightText: Copyright The Inferrix Authors
# SPDX-License-Identifier: Apache-2.0
#

"""Measure how far Cortex's schema-driven gateway forms are from the stack's own.

Reads two things and compares them:

  * the schema document a live gateway publishes, saved from
    `GET /api/inferrix/gateways/{deviceId}/schemas`
  * the stack webapp's own Angular source, whose per-type components are the only place the
    intended field order, grouping and label keys exist

Neither is modified. The stack webapp is read-only to this repo.

Usage:
    python3 ds-ui-gap.py <schemas.json> [<inferrixstack-webapp>/src/app]

Numbers this prints are the ones quoted in docs/features/gateway-datasource-ui-parity.md.
"""
import collections
import glob
import io
import json
import os
import re
import sys

WEBAPP_DEFAULT = os.path.expanduser('~/Office/Product/inferrixstack-webapp/src/app')

# What Cortex's own form already drops, from inferrix-gateway-data.models.ts. Kept in sync by
# hand: this script measures the gap, it is not part of the build.
HIDDEN = {'enabled', 'purgePeriod', 'purgeOverride', 'editPermission'}
IDENTITY = {'id', 'xid', 'name', 'modelType'}
REPORTED = {'description', 'connectionDescription', 'configurationDescription'}

# `[(ngModel)]="modbusIpModel.timePeriod.timePeriod"` -> root `modbusIpModel`, path `timePeriod`.
BIND = re.compile(r'\[\(ngModel\)\]="([A-Za-z_][A-Za-z0-9_]*)((?:\.[A-Za-z0-9_]+)*)"')


def flatten(node, components, depth=0, seen=frozenset()):
    """The properties one model type carries, `allOf` and `$ref` resolved, in document order.

    Mirrors what `schemaToFormProperties` walks, including its depth bound -- a schema that
    nests past it is one Cortex would not render either, so counting it here would overstate.
    """
    if depth > 8:
        return collections.OrderedDict()
    if '$ref' in node:
        name = node['$ref'].split('/')[-1]
        if name in seen:
            return collections.OrderedDict()
        return flatten(components.get(name, {}), components, depth + 1, seen | {name})
    props = collections.OrderedDict()
    for sub in node.get('allOf', []):
        props.update(flatten(sub, components, depth + 1, seen))
    props.update(node.get('properties') or {})
    return props


def template_for(cls, imports, webapp):
    """The template file of a dispatch entry's component class."""
    path = imports.get(cls)
    if path:
        base = os.path.normpath(os.path.join(webapp, 'datasource/datasource-edit', path))
        found = (glob.glob(base + '/*.component.html') if os.path.isdir(base)
                 else [base + '.html'])
        if found and os.path.exists(found[0]):
            return found[0]
    # An `index.ts` barrel hides the real directory, so fall back to the class's own name.
    kebab = re.sub(r'(?<!^)(?=[A-Z])', '-', cls.replace('Component', '')).lower()
    found = glob.glob(f'{webapp}/datasource/components/**/{kebab}.component.html', recursive=True)
    return found[0] if found else None


# Around twenty types hand their whole Properties tab to one shared component. Their own
# template binds nothing, so following the delegation is the difference between "this type shows
# no fields" and "this type shows the seven every mesh node shows".
DELEGATES = {
    'app-sensor-datasource-form':
        'datasource/components/common/mesh-nodes-datasource-form/'
        'mesh-nodes-datasource-form.component.html'
}


def shown_fields(path, webapp):
    """Data source fields a stack template binds, in the order it binds them.

    Bindings whose root names a point or a locator belong to the Points tab of the same
    component and are counted separately -- this is the data source side only.
    """
    text = io.open(path, encoding='utf-8').read()
    for selector, shared in DELEGATES.items():
        if '<' + selector in text:
            shared_path = os.path.join(webapp, shared)
            if os.path.exists(shared_path):
                text += io.open(shared_path, encoding='utf-8').read()
    order = []
    for match in BIND.finditer(text):
        root, rest = match.group(1), match.group(2)
        if 'oint' in root or 'ocator' in root:
            continue
        field = rest.lstrip('.').split('.')[0] if rest else root
        if field and field not in order:
            order.append(field)
    return order


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    document = json.load(io.open(argv[1], encoding='utf-8'),
                         object_pairs_hook=collections.OrderedDict)
    webapp = argv[2] if len(argv) > 2 else WEBAPP_DEFAULT
    components = document['components']['schemas']
    sources = document['families']['dataSource']

    edit = io.open(os.path.join(webapp, 'datasource/datasource-edit/datasource-edit.component.ts'),
                   encoding='utf-8').read()
    dispatch = {m.group(1): m.group(2)
                for m in re.finditer(r"'([A-Z0-9_]+\.DS)'\s*:\s*(\w+)", edit)}
    imports = {}
    for match in re.finditer(r"import\s*\{([^}]*)\}\s*from\s*['\"]([^'\"]+)['\"]", edit):
        for cls in (c.strip() for c in match.group(1).split(',')):
            if cls:
                imports[cls] = match.group(2)

    print(f'{"type":38}{"schema":>7}{"shown":>7}{"hidden":>8}  template')
    totals = []
    for model_type in sorted(dispatch):
        node = sources.get(model_type)
        if node is None:
            print(f'{model_type:38}{"-":>7}{"-":>7}{"-":>8}  (type not in this schema)')
            continue
        props = set(flatten(node, components)) - HIDDEN - IDENTITY - REPORTED
        path = template_for(dispatch[model_type], imports, webapp)
        if not path:
            print(f'{model_type:38}{len(props):>7}{"?":>7}{"?":>8}  (template not found)')
            continue
        # Only the overlap is comparable: a stack template also binds identity fields and, on
        # some types, model properties this gateway's schema no longer declares.
        kept = props & set(shown_fields(path, webapp))
        totals.append((len(props), len(kept), len(props - kept)))
        print(f'{model_type:38}{len(props):>7}{len(kept):>7}{len(props - kept):>8}  '
              f'{os.path.relpath(path, webapp)}')

    print()
    print(f'types the gateway declares:        {len(sources)}')
    print(f'types with a stack form:           {len(totals)}')
    print(f'types with no stack form:          {len(set(sources) - set(dispatch))}')
    print(f'stack entries this gateway lacks:  {len(set(dispatch) - set(sources))}')
    print(f'schema properties (configurable):  {sum(t[0] for t in totals)}')
    print(f'  shown by the stack:              {sum(t[1] for t in totals)}')
    print(f'  hidden by the stack:             {sum(t[2] for t in totals)}')


if __name__ == '__main__':
    main(sys.argv)
