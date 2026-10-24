package dowob.xyz.blog.module.article.facade;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 測試用的 WARN 日誌擷取器（本專案日誌後端為 Log4j2）。
 *
 * <p>建立時掛一個記憶體 appender 到指定 logger 所屬的 LoggerConfig，{@link #close()} 時移除，
 * 用 try-with-resources 或 {@code @AfterEach} 確保不污染其他測試。只回傳該 logger 名稱發出的 WARN 訊息。</p>
 *
 * @author Yuan
 * @version 1.0
 */
final class WarnLogCapture implements AutoCloseable {

    private final String loggerName;
    private final Configuration config;
    private final LoggerConfig loggerConfig;
    private final boolean createdLoggerConfig;
    private final Level originalLevel;
    private final LoggerContext context;
    private final AbstractAppender appender;
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

    /**
     * 開始擷取。
     *
     * @param loggerClass 要擷取其日誌的類別
     */
    WarnLogCapture(Class<?> loggerClass) {
        this.loggerName = loggerClass.getName();
        this.context = (LoggerContext) LogManager.getContext(false);
        this.config = context.getConfiguration();
        LoggerConfig existing = config.getLoggerConfig(loggerName);
        /*
         * 沒有專屬 LoggerConfig 時（預設 root 層級可能是 ERROR，WARN 會在進 appender 前就被濾掉），
         * 建一個專屬於該類別的 LoggerConfig 並把層級降到 WARN；close() 時整個移除還原。
         */
        if (loggerName.equals(existing.getName())) {
            this.loggerConfig = existing;
            this.createdLoggerConfig = false;
            this.originalLevel = existing.getLevel();
            existing.setLevel(Level.WARN);
        } else {
            this.loggerConfig = new LoggerConfig(loggerName, Level.WARN, true);
            this.createdLoggerConfig = true;
            this.originalLevel = null;
            config.addLogger(loggerName, loggerConfig);
        }
        this.appender = new AbstractAppender("warn-capture-" + loggerName, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN && loggerName.equals(event.getLoggerName())) {
                    warnings.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        loggerConfig.addAppender(appender, Level.WARN, null);
        context.updateLoggers();
    }

    /**
     * 目前為止擷取到的 WARN 訊息（已格式化）。
     *
     * @return 訊息快照
     */
    List<String> warnings() {
        synchronized (warnings) {
            return List.copyOf(warnings);
        }
    }

    @Override
    public void close() {
        loggerConfig.removeAppender(appender.getName());
        appender.stop();
        if (createdLoggerConfig) {
            config.removeLogger(loggerName);
        } else {
            loggerConfig.setLevel(originalLevel);
        }
        context.updateLoggers();
    }
}
