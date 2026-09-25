/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2022-2024 crDroid Android Project
 * Copyright (C) 2025 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.gamespace.gamebar

import android.annotation.SuppressLint
import android.app.GameManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemProperties
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager
import com.android.axion.platform.AxPlatformClient
import dagger.hilt.android.AndroidEntryPoint
import com.google.gson.Gson
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.GameSession
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.gamebar.brightness.BrightnessInteractor
import io.chaldeaprjkt.gamespace.gamebar.fps.FpsInteractor
import io.chaldeaprjkt.gamespace.gamebar.mapper.MapperController
import io.chaldeaprjkt.gamespace.gamebar.tiles.TileRepository
import io.chaldeaprjkt.gamespace.gamebar.tiles.ToggleableTile
import io.chaldeaprjkt.gamespace.utils.GameModeUtils
import io.chaldeaprjkt.gamespace.utils.ScreenUtils
import io.chaldeaprjkt.gamespace.utils.isServiceRunning
import lineageos.hardware.LineageHardwareManager
import javax.inject.Inject

@AndroidEntryPoint(Service::class)
class SessionService : Hilt_SessionService() {
    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var settings: SystemSettings
    @Inject lateinit var session: GameSession
    @Inject lateinit var screenUtils: ScreenUtils
    @Inject lateinit var gameModeUtils: GameModeUtils
    @Inject lateinit var callListener: CallListener
    @Inject lateinit var danmakuService: DanmakuService
    @Inject lateinit var brightnessInteractor: BrightnessInteractor
    @Inject lateinit var fpsInteractor: FpsInteractor
    @Inject lateinit var tileRepository: TileRepository
    @Inject lateinit var gson: Gson

    private var currentPackage: String? = null
    private lateinit var gameManager: GameManager
    private lateinit var sidebar: GameSidebar
    private lateinit var mapperController: MapperController
    private lateinit var platform: AxPlatformClient
    private lateinit var crosshairController: CrosshairController
    private lateinit var musicController: MusicController
    private lateinit var edgeMistouchController: EdgeMistouchController

    private var previousTouchPollingRate: Boolean = false
    private var previousTouchBoost: String = "0"

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (
            key.endsWith(AppSettings.KEY_CROSSHAIR_ENABLED) ||
            key.endsWith(AppSettings.KEY_CROSSHAIR_STYLE) ||
            key.endsWith(AppSettings.KEY_CROSSHAIR_SIZE) ||
            key.endsWith(AppSettings.KEY_CROSSHAIR_COLOR) ||
            key.endsWith(AppSettings.KEY_CROSSHAIR_OPACITY) ||
            key.endsWith(AppSettings.KEY_CROSSHAIR_OFFSET_X) ||
            key.endsWith(AppSettings.KEY_CROSSHAIR_OFFSET_Y)
        )) {
            crosshairController.updateState()
        }

        if (key != null && key.endsWith(AppSettings.KEY_MUSIC_PLAYER_ENABLED)) {
            if (appSettings.musicPlayerEnabled) {
                musicController.register()
            } else {
                musicController.unregister()
                musicController.hasActiveMedia.value = false
            }
        }

        if (key != null && (
            key.endsWith(AppSettings.KEY_EDGE_MISTOUCH_ENABLED) ||
            key.endsWith(AppSettings.KEY_EDGE_MISTOUCH_SIZE) ||
            key.endsWith(AppSettings.KEY_EDGE_MISTOUCH_CORNERS) ||
            key.endsWith(AppSettings.KEY_EDGE_MISTOUCH_FEEDBACK) ||
            key.endsWith(AppSettings.KEY_LOCK_GESTURE)
        )) {
            edgeMistouchController.updateState()
        }
    }

    private var dndEnabledByUs = false
    private var previousDndFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    @SuppressLint("WrongConstant")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SessionService created")

        platform = AxPlatformClient.getInstance()
        platform.init(this)

        gameManager = getSystemService(Context.GAME_SERVICE) as GameManager
        gameModeUtils.bind(gameManager)

        tileRepository.init(platform)

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val mainHandler = Handler(Looper.getMainLooper())

        crosshairController = CrosshairController(this, windowManager, appSettings)
        musicController = MusicController(this)
        edgeMistouchController = EdgeMistouchController(this, windowManager, appSettings)

        mapperController = MapperController(
            context = this,
            wm = windowManager,
            handler = mainHandler,
            gson = gson,
        )

        sidebar = GameSidebar(
            context = this,
            wm = windowManager,
            handler = mainHandler,
            appSettings = appSettings,
            screenUtils = screenUtils,
            danmakuService = danmakuService,
            brightnessInteractor = brightnessInteractor,
            fpsInteractor = fpsInteractor,
            gameModeUtils = gameModeUtils,
            settings = settings,
            tileRepository = tileRepository,
            platform = platform,
            mapperController = mapperController,
            musicController = musicController,
        )
        sidebar.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (packageName != null) {
                    startGameSession(packageName)
                } else {
                    Log.e(TAG, "No package name provided, stopping")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopGameSession()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sidebar.onConfigurationChanged(newConfig)
        edgeMistouchController.onConfigurationChanged(newConfig)
    }

    private fun startGameSession(packageName: String) {
        if (currentPackage == packageName) {
            Log.d(TAG, "Session already active for $packageName")
            return
        }
        
        if (currentPackage != null) {
            stopGameSession()
        }
        
        Log.i(TAG, "Starting game session for $packageName")
        currentPackage = packageName
        
        session.unregister()
        session.register(packageName)
        
        applyGameModeConfig(packageName)
        
        applyAutoDnd()

        appSettings.activeGamePackage = packageName
        val crosshairTile = tileRepository.allAvailableTiles.find { it.id == "crosshair" } as? ToggleableTile
        crosshairTile?.state?.value = appSettings.crosshairEnabled
        val mistouchTile = tileRepository.allAvailableTiles.find { it.id == "mistouch" } as? ToggleableTile
        mistouchTile?.state?.value = appSettings.edgeMistouchEnabled

        if (appSettings.musicPlayerEnabled) {
            musicController.register()
        }

        appSettings.registerListener(preferenceListener)
        crosshairController.updateState()
        edgeMistouchController.start()

        val hardwareManager = runCatching { LineageHardwareManager.getInstance(this) }.getOrNull()
        if (hardwareManager?.isSupported(LineageHardwareManager.FEATURE_HIGH_TOUCH_POLLING_RATE) == true) {
            previousTouchPollingRate = hardwareManager.get(LineageHardwareManager.FEATURE_HIGH_TOUCH_POLLING_RATE)
            hardwareManager.set(LineageHardwareManager.FEATURE_HIGH_TOUCH_POLLING_RATE, true)
        }
        previousTouchBoost = SystemProperties.get("persist.sys.touchboost_enable", "0")
        if (appSettings.touchBoostEnabled) {
            SystemProperties.set("persist.sys.touchboost_enable", "1")
        }

        sidebar.onGameStart(packageName)

        callListener.init()

        notifyPowerProfile(packageName, true)
    }

    private fun stopGameSession() {
        Log.i(TAG, "Stopping game session")
        currentPackage?.let { notifyPowerProfile(it, false) }

        appSettings.unregisterListener(preferenceListener)
        appSettings.activeGamePackage = null
        crosshairController.hideCrosshair()
        musicController.unregister()
        edgeMistouchController.stop()

        val hardwareManager = runCatching { LineageHardwareManager.getInstance(this) }.getOrNull()
        if (hardwareManager?.isSupported(LineageHardwareManager.FEATURE_HIGH_TOUCH_POLLING_RATE) == true) {
            hardwareManager.set(LineageHardwareManager.FEATURE_HIGH_TOUCH_POLLING_RATE, previousTouchPollingRate)
        }
        SystemProperties.set("persist.sys.touchboost_enable", previousTouchBoost)

        sidebar.onGameLeave()
        session.unregister()
        callListener.destroy()
        restoreAutoDnd()

        currentPackage = null
    }

    /**
     * Tells the device's power profiles (XiaomiParts) that a listed game is in
     * front, so every profile gives it the same treatment: PowerHAL GAME hint,
     * the game's thermal scene, 120Hz lock and high touch sampling. Without
     * this only AUTO noticed games, and Gaming/Balanced behaved differently
     * from AUTO for the same match.
     */
    private fun notifyPowerProfile(packageName: String, active: Boolean) {
        try {
            val intent = Intent(ACTION_GAME_SESSION).apply {
                setPackage(PARTS_PACKAGE)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_ACTIVE, active)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            sendBroadcastAsUser(intent, UserHandle.SYSTEM)
        } catch (e: Exception) {
            Log.w(TAG, "Could not notify power profiles", e)
        }
    }

    private fun applyAutoDnd() {
        if (!appSettings.autoDnd) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val currentFilter = nm.currentInterruptionFilter
        if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            currentFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            previousDndFilter = currentFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            dndEnabledByUs = true
        }
    }

    private fun restoreAutoDnd() {
        if (!dndEnabledByUs) return
        dndEnabledByUs = false
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.setInterruptionFilter(previousDndFilter)
    }

    private fun applyGameModeConfig(app: String) {
        val userGame = settings.userGames.firstOrNull { it.packageName == app }
        val preferred = userGame?.mode ?: GameModeUtils.defaultPreferredMode
        
        gameModeUtils.activeGame = userGame
        
        val availableModes = gameManager.getAvailableGameModes(app)
        if (availableModes.contains(preferred)) {
            gameManager.setGameMode(app, preferred)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "SessionService destroyed")
        stopGameSession()
        tileRepository.dispose()
        gameModeUtils.unbind()
        danmakuService.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "SessionService"
        const val ACTION_START = "game_start"
        const val ACTION_STOP = "game_stop"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ACTIVE = "active"
        const val ACTION_GAME_SESSION = "org.lineageos.settings.power.action.GAME_SESSION"
        const val PARTS_PACKAGE = "org.lineageos.settings"

        fun start(context: Context, app: String) {
            if (!context.isServiceRunning(SessionService::class.java)) {
                Intent(context, SessionService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_PACKAGE_NAME, app)
                }.let {
                    context.startServiceAsUser(it, UserHandle.CURRENT)
                }
            }
        }

        fun stop(context: Context) {
            if (context.isServiceRunning(SessionService::class.java)) {
                Intent(context, SessionService::class.java).apply {
                    action = ACTION_STOP
                }.let {
                    context.startServiceAsUser(it, UserHandle.CURRENT)
                }
            }
        }
    }
}
