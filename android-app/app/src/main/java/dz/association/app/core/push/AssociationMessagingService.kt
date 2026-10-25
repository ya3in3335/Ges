package dz.association.app.core.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dz.association.app.MainActivity
import dz.association.app.R
import javax.inject.Inject

@AndroidEntryPoint
class AssociationMessagingService : FirebaseMessagingService() {
    @Inject lateinit var push: PushManager

    override fun onNewToken(token: String) {
        push.scheduleSync()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        // Only internal app:// deep links are honoured.
        val link = message.data["deepLink"]?.takeIf { it.startsWith("app://") }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (link != null) putExtra(MainActivity.EXTRA_DEEP_LINK, link)
        }
        val pi = PendingIntent.getActivity(this, link.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, PushManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(this).notify((message.messageId ?: n.title).hashCode(), notification)
    }
}
