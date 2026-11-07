package dowob.xyz.blog.module.search.reindex;

/**
 * 在中斷旗標暫時清除的狀態下執行收尾動作，結束後再設回（審查 m1）。
 *
 * <p>應用程式關閉時 {@code executor.shutdownNow()} 會中斷重建執行緒。中斷旗標仍在時，Lettuce（Redis）與 ES RestClient
 * 的同步呼叫會直接丟例外，收尾（刪新索引、結束變動紀錄、寫 FAILED、解鎖）全部失敗：留下孤兒索引、鎖卡到 TTL 到期、
 * 狀態一直顯示 RUNNING。收尾本身是短暫且必要的 I/O，先清掉旗標讓它們能完成；收尾後設回旗標，
 * 讓呼叫端與執行緒池仍看得到「這條執行緒被要求停止」。</p>
 *
 * @author Yuan
 * @version 1.0
 */
final class InterruptSafe {

    /**
     * 靜態工具類別，不提供實例化。
     */
    private InterruptSafe() {
    }

    /**
     * 清除中斷旗標後執行 {@code cleanup}，結束（含拋例外）後若原本有中斷則設回。
     *
     * @param cleanup 收尾動作
     */
    static void run(Runnable cleanup) {
        boolean interrupted = Thread.interrupted();
        try {
            cleanup.run();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
