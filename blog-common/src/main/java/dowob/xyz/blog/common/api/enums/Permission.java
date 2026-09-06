package dowob.xyz.blog.common.api.enums;

/**
 * 系統細粒度操作權限枚舉（~12 個）
 *
 * <p>
 * 依 plan2.md §7.2.1 定義所有中粒度操作權限，供 {@link Role} 進行靜態對應。
 * 各權限可透過 Spring Security 的 {@code hasAuthority('PERMISSION_NAME')} 進行宣告式存取控制。
 * </p>
 *
 * <p>
 * <b>設計原則</b>：權限僅控制「能不能做」，資源所有權（OWN vs ANY）在 Service 層透過
 * {@code OwnershipChecker} 驗證；Admin 自動 bypass 所有權檢查。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum Permission {

    /** 建立文章（AUTHOR 及以上角色擁有） */
    ARTICLE_CREATE,

    /** 編輯文章（AUTHOR 及以上角色擁有） */
    ARTICLE_EDIT,

    /** 刪除文章（AUTHOR 及以上角色擁有） */
    ARTICLE_DELETE,

    /** 置頂文章（AUTHOR 可置頂自己的；ADMIN bypass 所有權檢查） */
    ARTICLE_PIN,

    /** 發表留言（USER 及以上角色擁有） */
    COMMENT_WRITE,

    /** 刪除留言（USER 可刪自己的；ADMIN bypass 所有權檢查） */
    COMMENT_DELETE,

    /** 上傳檔案（AUTHOR 及以上角色擁有） */
    FILE_UPLOAD,

    /** 管理標籤（僅 ADMIN） */
    TAG_MANAGE,

    /** 管理用戶（僅 ADMIN） */
    USER_MANAGE,

    /** 封禁用戶（僅 ADMIN） */
    USER_BAN,

    /** 系統設定（僅 ADMIN） */
    SYSTEM_CONFIG,

    /** 發布系統公告（僅 ADMIN） */
    SYSTEM_ANNOUNCE
}
