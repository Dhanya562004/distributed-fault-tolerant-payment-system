import csv
from collections import defaultdict

packages = defaultdict(lambda: {'lc': 0, 'lm': 0, 'bc': 0, 'bm': 0})
with open('target/site/jacoco/jacoco.csv', mode='r') as f:
    reader = csv.DictReader(f)
    for row in reader:
        pkg = row['PACKAGE']
        packages[pkg]['lc'] += int(row['LINE_COVERED'])
        packages[pkg]['lm'] += int(row['LINE_MISSED'])
        packages[pkg]['bc'] += int(row['BRANCH_COVERED'])
        packages[pkg]['bm'] += int(row['BRANCH_MISSED'])

print(f"{'Package':<35} | {'Line Cov':<10} | {'Branch Cov':<10} | Lines | Branches")
print('-' * 80)
for pkg, d in sorted(packages.items()):
    ltot = d['lc'] + d['lm']
    btot = d['bc'] + d['bm']
    l_pct = (d['lc'] / ltot * 100) if ltot > 0 else 100.0
    b_pct = (d['bc'] / btot * 100) if btot > 0 else 100.0
    print(f"{pkg:<35} | {l_pct:6.2f}%    | {b_pct:6.2f}%    | {d['lc']}/{ltot} | {d['bc']}/{btot}")
