package app.mauri.cucuto.backup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface BackupModule {
    @Binds
    fun backupGateway(gateway: AppBackupGateway): BackupGateway

    @Binds
    fun autoBackupControl(autoBackup: AutoBackup): AutoBackupControl
}
