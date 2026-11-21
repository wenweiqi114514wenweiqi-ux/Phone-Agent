#!/usr/bin/env bash
# 一键发布到 GitHub：建仓库 → 推代码与 tag → 建 Release 并附上 APK
#
# 用法：
#   GITHUB_TOKEN=ghp_xxx ./scripts/publish_github.sh                 # 仓库名默认 phone-agent，公开
#   GITHUB_TOKEN=ghp_xxx ./scripts/publish_github.sh my-repo private # 自定义名字 / 私有
#
# token 需要 repo 权限（classic token 勾 repo；fine-grained 勾 Repository contents: read/write
# 与 Administration: write，若要建仓库还需 "Create repositories" 权限）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${GITHUB_TOKEN:?请先设置 GITHUB_TOKEN 环境变量}"
REPO_NAME="${1:-phone-agent}"
VISIBILITY="${2:-public}"
TAG="v1.0.13"
APK="app/build/outputs/apk/debug/app-debug.apk"
NOTES="docs/RELEASE_NOTES_v1.0.13.md"

API="https://api.github.com"
AUTH=(-H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Accept: application/vnd.github+json")

json_get() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1',''))"; }

LOGIN="$(curl -s "${AUTH[@]}" "$API/user" | json_get login)"
if [ -z "$LOGIN" ]; then
  echo "✗ token 无效或没有读取用户的权限" >&2
  exit 1
fi
echo "→ GitHub 账号：$LOGIN"

# 1) 建仓库（已存在则跳过）
CODE="$(curl -s -o /tmp/repo_check.json -w '%{http_code}' "${AUTH[@]}" "$API/repos/$LOGIN/$REPO_NAME")"
if [ "$CODE" = "200" ]; then
  echo "→ 仓库已存在：$LOGIN/$REPO_NAME"
else
  echo "→ 创建仓库 $LOGIN/$REPO_NAME（$VISIBILITY）"
  curl -s -X POST "${AUTH[@]}" "$API/user/repos" -d "$(python3 - "$REPO_NAME" "$VISIBILITY" <<'PY'
import json,sys
name,vis=sys.argv[1],sys.argv[2]
print(json.dumps({
  "name": name,
  "description": "把手机当成一台可以被 AI 直接操作的小电脑：自然语言驱动，工作区文件操作与内置预览。",
  "private": vis == "private",
  "has_issues": True, "has_wiki": False, "has_projects": False,
  "auto_init": False,
})) 
PY
)" >/tmp/repo_create.json
  python3 -c "import json;d=json.load(open('/tmp/repo_create.json'));print('   ',d.get('html_url') or d.get('message'))"
fi

# 2) 推代码（token 只用于这一次 push，不写进 .git/config）
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$LOGIN/$REPO_NAME.git"
git push "https://x-access-token:${GITHUB_TOKEN}@github.com/$LOGIN/$REPO_NAME.git" HEAD:main --tags

# 3) 建 Release
echo "→ 创建 Release $TAG"
RELEASE_ID="$(curl -s -X POST "${AUTH[@]}" "$API/repos/$LOGIN/$REPO_NAME/releases" -d "$(python3 - "$TAG" "$NOTES" <<'PY'
import json,sys
tag,notes=sys.argv[1],sys.argv[2]
print(json.dumps({
  "tag_name": tag,
  "name": "v1.0.13 · 首个公开版本",
  "body": open(notes, encoding="utf-8").read(),
  "draft": False, "prerelease": False,
}))
PY
)" | json_get id)"

if [ -z "$RELEASE_ID" ]; then
  echo "✗ Release 创建失败（可能 tag 未推送成功）" >&2
  exit 1
fi
echo "→ Release id: $RELEASE_ID"

# 4) 上传 APK 作为 Release 附件
if [ -f "$APK" ]; then
  echo "→ 上传 APK（$(du -h "$APK" | cut -f1)）"
  curl -s -X POST \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$APK" \
    "https://uploads.github.com/repos/$LOGIN/$REPO_NAME/releases/$RELEASE_ID/assets?name=PhoneAgent-1.0.13.apk" \
    | json_get state
else
  echo "! 没找到 $APK，跳过附件上传（可先 ./gradlew assembleDebug）"
fi

echo "✓ 完成：https://github.com/$LOGIN/$REPO_NAME/releases/tag/$TAG"
