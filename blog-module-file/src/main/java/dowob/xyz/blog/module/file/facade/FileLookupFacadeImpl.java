package dowob.xyz.blog.module.file.facade;

import dowob.xyz.blog.infrastructure.facade.FileLookupFacade;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import dowob.xyz.blog.module.file.model.UsageType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link FileLookupFacade} 實作：只依賴 {@link FileMetadataRepository} 的唯讀查詢。
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class FileLookupFacadeImpl implements FileLookupFacade {

    private final FileMetadataRepository fileMetadataRepository;

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAvatarOwnedBy(UUID fileUuid, UUID userUuid) {
        if (fileUuid == null || userUuid == null) {
            return false;
        }
        return fileMetadataRepository.findById(fileUuid)
                .filter(file -> file.getUsageType() == UsageType.AVATAR && userUuid.equals(file.getUploaderId()))
                .isPresent();
    }
}
