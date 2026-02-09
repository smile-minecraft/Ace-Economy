package com.smile.aceeconomy.migration;

import org.bukkit.command.CommandSender;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 資料遷移介面。
 * <p>
 * 定義從其他經濟插件匯入資料的通用介面。
 * </p>
 *
 * @author Smile
 */
public interface Migrator {

    /**
     * 取得遷移器名稱。
     *
     * @return 遷移器名稱
     */
    String getName();

    /**
     * 檢查來源插件是否可用。
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 執行資料遷移。
     *
     * @param sender           指令發送者（用於回報進度）
     * @param progressCallback 進度回調（傳入已處理數量）
     * @return 遷移結果，包含成功與失敗數量
     */
    CompletableFuture<MigrationResult> migrate(CommandSender sender, Consumer<Integer> progressCallback);

    /**
     * 遷移結果。
     */
    record MigrationResult(int successCount, int failCount, int totalCount) {
        /**
         * 是否有錯誤。
         *
         * @return 是否有錯誤
         */
        public boolean hasErrors() {
            return failCount > 0;
        }
    }
}
