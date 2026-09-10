package com.silab.smartcount

import android.app.Application
import androidx.room.Room
import com.silab.smartcount.data.api.CredentialStore
import com.silab.smartcount.data.api.TricountClient
import com.silab.smartcount.data.db.AppDatabase
import com.silab.smartcount.data.cache.GroupCache
import com.silab.smartcount.notif.BankRegistry
import com.silab.smartcount.notif.DetectionNotifier
import com.silab.smartcount.data.repo.MemberIdentity
import com.silab.smartcount.data.repo.SavingsGroups
import com.silab.smartcount.notif.NotificationRules

class SmartCountApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var credentials: CredentialStore
        private set
    lateinit var client: TricountClient
        private set
    lateinit var bankRegistry: BankRegistry
        private set
    lateinit var groupCache: GroupCache
        private set
    lateinit var notificationRules: NotificationRules
        private set
    lateinit var savingsGroups: SavingsGroups
        private set
    lateinit var memberIdentity: MemberIdentity
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, AppDatabase::class.java, "tricount-companion.db")
            .fallbackToDestructiveMigration()
            .build()
        credentials = CredentialStore.create(this)
        memberIdentity = MemberIdentity(this)
        // Todo grupo que salga del cliente pasa por aquí: así el balance también
        // aparece en los grupos donde la API no dice cuál de los miembros eres.
        client = TricountClient(credentials, resolveIdentity = memberIdentity::resolve)
        bankRegistry = BankRegistry(this)
        groupCache = GroupCache(this)
        notificationRules = NotificationRules(this)
        savingsGroups = SavingsGroups(this)
        DetectionNotifier.ensureChannel(this)
    }
}
