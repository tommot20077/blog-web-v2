package dowob.xyz.blog.module.article.model;

/**
 * 文章內容的長度上限常數（唯一定義處）
 *
 * @author Yuan
 * @version 1.0
 */
public final class ArticleContentLimits {

    /** 文章 Markdown 內文最大字元數 */
    public static final int MAX_CONTENT_LENGTH = 200_000;

    private ArticleContentLimits() {
    }
}
