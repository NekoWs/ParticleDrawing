# 发布到 Maven Central

本项目已配置好自动发布到 [Maven Central](https://central.sonatype.com/)（通过 Central Portal publisher API）。发布坐标：

```
group    : work.nekow.particledrawing
artifact : particledrawing
version  : 1.0.0（来自 gradle.properties 的 mod_version）
```

> groupId `work.nekow.particledrawing` 属于已注册的 namespace `work.nekow` 之下。

## 前提条件

1. 已在 Central Portal 注册 namespace（你已完成）。
2. 一个 GPG 密钥对（用于对构件签名，Maven Central 强制要求）。
3. 一个 Central Portal 的发布 Token（用户名 + 密码/token）。
4. GitHub 仓库的 4 个 Actions Secrets。

## 第 1 步：生成并导出 GPG 密钥

```bash
# 生成密钥（交互式，需设置 passphrase）
gpg --full-generate-key

# 查看密钥 ID（8 位十六进制）
gpg --list-secret-keys --keyid-format SHORT

# 导出 ASCII-armored 私钥（用于 GitHub Secret）
gpg --armor --export-secret-keys <KEY_ID> > private-key.asc
```

将 `private-key.asc` 的完整内容（含 `-----BEGIN PGP PRIVATE KEY BLOCK-----` 与结尾）保存下来。

## 第 2 步：把公钥上传到密钥服务器

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Maven Central 需要能从公钥服务器验证你的签名。

## 第 3 步：创建 Central Portal 发布 Token

1. 登录 [central.sonatype.com](https://central.sonatype.com/)。
2. 进入你的账户 → **Publish Portal** / 访问 Token 设置，生成一个 **User Token**。
3. 记录下 **username** 与 **token（password）**。

## 第 4 步：配置 GitHub Actions Secrets

在 GitHub 仓库 → **Settings → Secrets and variables → Actions** 中新增以下 Secrets：

| Secret 名称 | 内容 |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal 的 username |
| `MAVEN_CENTRAL_TOKEN` | Central Portal 的 token（password） |
| `GPG_PRIVATE_KEY` | 第 1 步导出的 ASCII-armored 私钥完整文本 |
| `GPG_PASSPHRASE` | GPG 密钥的 passphrase（无则留空） |

## 第 5 步：触发自动发布

推送一个 `v` 开头的 tag 即可触发 `.github/workflows/publish.yml`：

```bash
git tag v1.0.0
git push origin v1.0.0
```

工作流会自动：

1. 从 tag 派生版本号（`v1.0.0` → `1.0.0`）；
2. 编译并生成 `jar`、`-sources.jar`、`-javadoc.jar`；
3. 用 GPG 对三个构件签名；
4. 上传到 Central Portal，等待校验后自动发布。

发布成功后，可在 Maven Central 中检索到：

```
https://central.sonatype.com/artifact/work.nekow.particledrawing/particledrawing
```

## 本地测试（不发布到 Central）

本地验证产物与 POM（不签名、不上传）：

```bash
# Windows
.\gradlew.bat publishMavenJavaPublicationToLocalRepository

# Linux / macOS
./gradlew publishMavenJavaPublicationToLocalRepository
```

产物输出到项目根目录的 `repo/` 目录。

## 常见问题

- **签名失败 / 找不到密钥**：确认 `GPG_PRIVATE_KEY` 是完整的 ASCII-armored 私钥，且 `GPG_PASSPHRASE` 正确（无 passphrase 则设为空字符串）。
- **401 认证失败**：检查 `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_TOKEN` 是否正确。
- **校验失败：缺少 license / developers / scm**：这些已在 `build.gradle` 的 POM 中配置，无需改动。
- **依赖 `net.neoforged:neoforge` 不在 Central**：该依赖由 NeoForge 官方仓库提供，Central 允许声明外部仓库依赖；消费方通过模组加载器解析即可。

## 建议

- 在仓库根目录补一份 `LICENSE` 文件（Apache-2.0 全文），与 `mod_license=Apache-2.0` 保持一致。
- 发布前用本地仓库确认 POM 内容无误：`./gradlew generatePomFileForMavenJavaPublication` 后查看 `build/publications/mavenJava/pom-default.xml`。
