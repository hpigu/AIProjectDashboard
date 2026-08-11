package dev.aiboard.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BuildInfoProvider 的重點不在「有值時回傳該值」，而在「缺值時不會壞掉」：
 * BuildProperties 與 GitProperties 都是條件式 bean，從沒有 .git 的原始碼壓縮檔
 * 建置、或跳過 build-info goal 時就不存在。健康端點是啟動腳本與外部監控的依賴，
 * 不能因為少了建置中繼資料就丟 NullPointerException。
 */
class BuildInfoProviderTest {

    @Test
    void reportsVersionAndShortCommitWhenBothArePresent() {
        Properties build = new Properties();
        build.setProperty("version", "3.1.0");

        Properties git = new Properties();
        // GitProperties 讀的是 git.commit.id.abbrev，getShortCommitId() 會優先用它。
        git.setProperty("commit.id.abbrev", "f23cd31");

        BuildInfoProvider provider = new BuildInfoProvider(
                provider(new BuildProperties(build)), provider(new GitProperties(git)));

        assertThat(provider.version()).isEqualTo("3.1.0");
        assertThat(provider.commit()).isEqualTo("f23cd31");
    }

    @Test
    void fallsBackToUnknownWhenBuildMetadataIsAbsent() {
        BuildInfoProvider provider = new BuildInfoProvider(provider(null), provider(null));

        // 版本走 jar manifest 退路：測試是從 classes 目錄跑的，manifest 沒有值，
        // 因此這裡預期 unknown——重點是「不拋例外且有可讀的值」。
        assertThat(provider.version()).isEqualTo(BuildInfoProvider.UNKNOWN);
        assertThat(provider.commit()).isEqualTo(BuildInfoProvider.UNKNOWN);
    }

    @Test
    void treatsBlankCommitAsUnknownRatherThanEmptyString() {
        Properties git = new Properties();
        git.setProperty("commit.id.abbrev", "   ");

        BuildInfoProvider provider = new BuildInfoProvider(provider(null), provider(new GitProperties(git)));

        assertThat(provider.commit()).isEqualTo(BuildInfoProvider.UNKNOWN);
    }

    /** 只需要 getIfAvailable()，其餘 ObjectProvider 方法走介面預設實作即可。 */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getObject() {
                if (value == null) {
                    throw new IllegalStateException("no value");
                }
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }
}
