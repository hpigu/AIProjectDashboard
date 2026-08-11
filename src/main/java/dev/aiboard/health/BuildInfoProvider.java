package dev.aiboard.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

/**
 * 「這個行程到底是哪一版、哪一個 commit」的單一事實來源，供
 * {@link HealthService} 與 {@link DiagnosticsService} 共用。
 *
 * <p>抽出來的原因是原本兩邊各自呼叫
 * {@code getClass().getPackage().getImplementationVersion()}，那個值來自 jar 的
 * {@code MANIFEST.MF}，只有跑打包後的 jar 才存在；用 {@code mvnw spring-boot:run}
 * 或在 IDE 裡直接跑 main 時一律是 {@code null}，於是 {@code /api/health} 回報
 * {@code "unknown"}——偏偏開發中最需要確認版本的就是這些情境。
 *
 * <p>改用 Spring Boot 的 {@link BuildProperties}（由 spring-boot-maven-plugin 的
 * {@code build-info} goal 寫進 {@code META-INF/build-info.properties}）與
 * {@link GitProperties}（由 git-commit-id-maven-plugin 寫進 {@code git.properties}），
 * 兩者都在 <b>編譯期</b>產生到 {@code target/classes} 下，因此 jar、
 * {@code spring-boot:run} 與 IDE 三種啟動方式看到的值一致。
 *
 * <p>兩個 bean 都是條件式建立的（對應的 properties 檔不存在就不會有 bean），
 * 例如從沒有 {@code .git} 的原始碼壓縮檔建置時就不會有 {@link GitProperties}。
 * 因此一律用 {@link ObjectProvider} 取得並保留退路，缺任何一項都不能讓
 * 健康端點壞掉。
 */
@Component
public class BuildInfoProvider {

    static final String UNKNOWN = "unknown";

    private final String version;
    private final String commit;

    public BuildInfoProvider(ObjectProvider<BuildProperties> buildProperties,
                             ObjectProvider<GitProperties> gitProperties) {
        this.version = resolveVersion(buildProperties.getIfAvailable());
        this.commit = resolveCommit(gitProperties.getIfAvailable());
    }

    /** 專案版本，例如 {@code 3.1.0}；無法判定時為 {@code unknown}。 */
    public String version() {
        return version;
    }

    /**
     * 建置來源的 short commit hash，例如 {@code f23cd31}；無法判定時為
     * {@code unknown}。刻意只回 short hash 而非完整 hash 加分支名：這個值會出現在
     * 公開的 {@code /api/health}，short hash 已足夠對照版本，多餘的資訊沒有理由外露。
     */
    public String commit() {
        return commit;
    }

    private String resolveVersion(BuildProperties buildProperties) {
        if (buildProperties != null && hasText(buildProperties.getVersion())) {
            return buildProperties.getVersion();
        }
        // 退路：打包成 jar 時 manifest 仍會帶 Implementation-Version，
        // 即使 build-info.properties 因故缺席也還有值可用。
        String fromManifest = getClass().getPackage().getImplementationVersion();
        return hasText(fromManifest) ? fromManifest : UNKNOWN;
    }

    private String resolveCommit(GitProperties gitProperties) {
        if (gitProperties == null) {
            return UNKNOWN;
        }
        String shortId = gitProperties.getShortCommitId();
        return hasText(shortId) ? shortId : UNKNOWN;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
