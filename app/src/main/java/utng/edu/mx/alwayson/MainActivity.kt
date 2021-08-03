package utng.edu.mx.alwayson

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.wear.ambient.AmbientModeSupport
import utng.edu.mx.alwayson.databinding.ActivityMainBinding
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit


class MainActivity : FragmentActivity(), AmbientModeSupport.AmbientCallbackProvider {

    private lateinit var binding: ActivityMainBinding

    /**
     * El controlador de modo ambiental añadido para esta pantalla. Usada por la actividad
     * para ver si el esta en ambiente
     */
    private var mAmbientController: AmbientModeSupport.AmbientController? = null;


    /**
     * Si la pantalla en modo ambiental de bajo bit. Por ejemplo esto requiere fuentes
     * suavizadas.
     */
    private var mIsLowBitAmbient = false

    /**
     * Si la pantalla requiere proteccion contra quemaduras en modo ambiente, redenriza pixeles
     * necesarios para ser una compesacion intermintente para evitar que la pantalla se queme.
     */

    private var mDoBurnInProtection = false
    private var mContentView: View? = null
    private var mTimeTextView: TextView? = null
    private var mTimeStampTextView: TextView? = null
    private var mStateTextView: TextView? = null
    private var mUpdateRateTextView: TextView? = null
    private var mDrawCountTextView: TextView? = null
    private val sDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var mDrawCount = 0

    /**
     * Desde manejar (usado en modo activo) no se puede levantar el procesador cuando el dispositivo
     * esta en modo ambiente y desacoplado, nosotros usamos una alarma para cubrir las actualizaciones
     * de modo ambiente cuando necesitamos de ellos mas frecuentemente como cada minuto.
     * Recuerda, si obtenemos actualizaciones una vez en un minuto en modo ambiente, es suficiente,
     * puedes terminar con el código de alarma y confiar en llamar nuevamente el método
     * onUpdateAmbient
     */

    private var mAmbientUpdateAlarmManager: AlarmManager? = null
    private var mAmbientUpdatePendingIntent: PendingIntent? = null
    private var mAmbientUpdateBroadcastReceiver: BroadcastReceiver? = null

    /**
     * Este manejador personalizado es usada para actualizaciones en modo "Activo". Usaremos una
     * clase estática separada para ayudarnos a evitar fugas de memoria.
     */

    private val mActiveModeUpdateHandler: Handler = ActiveModeUpdateHandler(this)

    /** Conjunto de elementos estáticos de la clase */
    companion object {
        private const val TAG = "MainActivity"

        /**Mensaje personalizado mandado al Manejador*/
        private const val MSG_UPDATE_SCREEN = 0

        /**Milisegundos entre actualizaciones basados en estado*/
        private val ACTIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1)
        private val AMBIENT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(10)

        /**Acción para actulizar la pantalla en modo ambiente, por ciclo de actualización
         * personalizado */
        private const val AMBIENT_UPDATE_ACTION = "utng.edu.mx.always_on.action.AMBIENT_UPDATE"

        /**El numero de pixeles para compensar el contenido renderizado en la pantalla
         * para prevenir que la pantalla se queme. */
        const val BURN_IN_OFFSET_PX = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate()")
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mAmbientController = AmbientModeSupport.attach(this)
        mAmbientUpdateAlarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        /**
         * Crea un PendingIntent el cual nos dará el AlarmaManager para enviar actualizaciones al
         * modo ambiente en un intervalo el cual hemos definido
         */
        val ambientUpdateIntent = Intent(AMBIENT_UPDATE_ACTION)

        /**
         * Recupera un PendingIntent que realizara una transmisión. Podrias tambipen usar getActivity()
         * para recuperar un PendingIntent que iniciará una nueva actividad, pero ten en cuenta que
         * en realidad dispara onNewIntent() el cual provoca que el ciclo de vida cambie (onPause y onResume)
         * lo que podria hacer que el código se ejecute con más frecuencia de la que se desea.
         *
         * Si terminas usando getActivity(), tambien asegurate de haber configurado la actividad
         * launchMode a singleInstance en el manifest.
         *
         * De otra manera, esto es facil para el AlertManager lanzar un intento que abra una nueva actividad
         * cada vez que la alarma se activa en lugar de reusar esta actividad.
         */

        mAmbientUpdatePendingIntent = PendingIntent.getBroadcast(
            this, 0, ambientUpdateIntent, PendingIntent.FLAG_UPDATE_CURRENT
        )

        /**
         * Un receptor de transmisión anónimo que recibirá solicitudes de actualización ambiental
         * y activará la actualizacion de la pantalla.
         */
        mAmbientUpdateBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                refreshDisplayAndSetNextUpdate()
            }
        }

        mContentView = findViewById(R.id.content_view)
        mTimeTextView = findViewById(R.id.time)
        mTimeStampTextView = findViewById(R.id.time_stamp)
        mStateTextView = findViewById(R.id.state)
        mUpdateRateTextView = findViewById(R.id.update_rate)
        mDrawCountTextView = findViewById(R.id.draw_count)
    }

    override fun onResume() {
        Log.d(TAG, "onResume()")
        super.onResume()
        val filter = IntentFilter(AMBIENT_UPDATE_ACTION)
        registerReceiver(mAmbientUpdateBroadcastReceiver, filter)
        refreshDisplayAndSetNextUpdate()
    }

    override fun onPause() {
        Log.d(TAG, "onPause()")
        super.onPause()
        unregisterReceiver(mAmbientUpdateBroadcastReceiver)
        mActiveModeUpdateHandler.removeMessages(MSG_UPDATE_SCREEN)
        mAmbientUpdateAlarmManager!!.cancel(mAmbientUpdatePendingIntent)
    }

    /**
     * Actualiza la pantalla en base a el estado ambiente. Si necesitas extraer datos lo debes
     * hacer aqui
     */
    private fun loadDataAndUpdateScreen() {
        mDrawCount += 1
        val currentTimeMs = System.currentTimeMillis()
        Log.d(TAG, "loadDataAndUpdateScreen $currentTimeMs (${mAmbientController!!.isAmbient})")

        mTimeTextView!!.text = sDateFormat.format(Date())
        mTimeStampTextView!!.text = getString(R.string.timestamp_label, currentTimeMs)

        if (mAmbientController!!.isAmbient) {

            mStateTextView!!.text = getString(R.string.mode_ambient_label)
            mUpdateRateTextView!!.text =
                getString(R.string.update_rate_label, AMBIENT_INTERVAL_MS / 1000)
        } else {
            mStateTextView!!.text = getString(R.string.mode_active_label)
            mUpdateRateTextView!!.text =
                getString(R.string.update_rate_label, ACTIVE_INTERVAL_MS / 1000)

        }
        mDrawCountTextView!!.text = getString(R.string.draw_count_label, mDrawCount)

    }

    /**
     * Carga la pantalla de datos / actualizaciones (a traves del método), pero lo más importante,
     * configura la próxima actualización(modo activo = Manejador y modo ambiente = alarma)
     */
    private fun refreshDisplayAndSetNextUpdate() {
        loadDataAndUpdateScreen()
        val timeMs = System.currentTimeMillis()
        if (mAmbientController!!.isAmbient) {
            /* Calcula el próximo tiempo de activación (según el estado) */
            val delayMs = AMBIENT_INTERVAL_MS - timeMs % AMBIENT_INTERVAL_MS
            val triggerTimeMs = timeMs + delayMs
            mAmbientUpdateAlarmManager!!.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerTimeMs,
                mAmbientUpdatePendingIntent
            )
        } else {
            /* Calcula el próximo tiempo de activación (según el estado) */
            val delayMs = ACTIVE_INTERVAL_MS - timeMs % ACTIVE_INTERVAL_MS
            mActiveModeUpdateHandler.removeMessages(MSG_UPDATE_SCREEN)
            mActiveModeUpdateHandler.sendEmptyMessageDelayed(MSG_UPDATE_SCREEN, delayMs)
        }
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return MyAmbientCallBack()

    }

    private inner class MyAmbientCallBack : AmbientModeSupport.AmbientCallback() {
        /*Prepara la interfaz de usuario para modo ambietn */
        override fun onEnterAmbient(ambientDetails: Bundle?) {
            super.onEnterAmbient(ambientDetails)
            mIsLowBitAmbient =
                ambientDetails!!.getBoolean(AmbientModeSupport.EXTRA_LOWBIT_AMBIENT, false)
            mDoBurnInProtection =
                ambientDetails!!.getBoolean(AmbientModeSupport.EXTRA_BURN_IN_PROTECTION, false)
            /* Borra la cola del manejador. (Solo necesita actualizaciones en modo activo)*/
            mActiveModeUpdateHandler.removeMessages(MSG_UPDATE_SCREEN)
            /**
             * Seguir las mejores prácticas descritas en el API de WatchFaces (manteniendo la
             * mayoria de los piexeles negros, evitando grandes bloques de pixeles blancos y
             * desactivando el suavizado, etc.)
             */
            mStateTextView!!.setTextColor(Color.WHITE)
            mUpdateRateTextView!!.setTextColor(Color.WHITE)
            mDrawCountTextView!!.setTextColor(Color.WHITE)
            if (mIsLowBitAmbient) {
                mTimeTextView!!.paint.isAntiAlias = false
                mTimeStampTextView!!.paint.isAntiAlias = false
                mStateTextView!!.paint.isAntiAlias = false
                mUpdateRateTextView!!.paint.isAntiAlias = false
                mDrawCountTextView!!.paint.isAntiAlias = false
            }

            refreshDisplayAndSetNextUpdate()
        }

        /**
         * Actualiza la pantalla en modo ambiente en el intervalo estándar. Desde que usamos
         * el ciclo de actualización personalizado, este método no actualizará los datos en la
         * pantalla. Más bien este mpetodo simplemente actualiza la posición de los datos en la
         * pantalla y evita que se queme si la pantalla lo requiere.
         */

        override fun onUpdateAmbient() {
            super.onUpdateAmbient()
            /**
             * Si la pantalla requiere protección contra quemaduras, las vistas deben cambiarse
             * periódicamente en el modo ambiental. Par segurarse de que el contenido no se mueva
             * fuera de la pantalla, evite colocar contenido dentro de los 10 pixeles del borde
             * de la pantalla. Dado que potencialmente estamos aplicando relleno negativo.
             * Nos hemos asegurado de que la vista contenedora este suficientemente rellena (ver
             * res/layout/activity_main.xml). Las actividades tambipen deben evitar las áreas blancas
             * sólidas para evitar el desgaste de pixeles. Ambas requisitos solo se aplican en modo
             * ambiente y solo cuando esta propiedad se establece en verdadera.
             */
            if (mDoBurnInProtection) {
                val x = (Math.random() * 2 * BURN_IN_OFFSET_PX - BURN_IN_OFFSET_PX).toInt()
                val y = (Math.random() * 2 * BURN_IN_OFFSET_PX - BURN_IN_OFFSET_PX).toInt()
                mContentView!!.setPadding(x, y, 0, 0)
            }
        }

        //Restuara la UI para modo activo (no ambiental)
        override fun onExitAmbient() {
            super.onExitAmbient()
            //Elimina las alarmas ya que solo se utilizan en modo ambiente
            mAmbientUpdateAlarmManager!!.cancel(mAmbientUpdatePendingIntent)
            mStateTextView!!.setTextColor(Color.GREEN)
            mUpdateRateTextView!!.setTextColor(Color.GREEN)
            mDrawCountTextView!!.setTextColor(Color.GREEN)
            if (mIsLowBitAmbient) {
                mTimeTextView!!.paint.isAntiAlias = true
                mTimeStampTextView!!.paint.isAntiAlias = true
                mStateTextView!!.paint.isAntiAlias = true
                mUpdateRateTextView!!.paint.isAntiAlias = true
                mDrawCountTextView!!.paint.isAntiAlias = true
            }

            // Restablece cualquier protección aleatoria aplicada para protección contra quemaduras
            if (mDoBurnInProtection) {
                mContentView!!.setPadding(0, 0, 0, 0)
            }

            refreshDisplayAndSetNextUpdate()
        }
    }

    private class ActiveModeUpdateHandler internal constructor(
        reference: MainActivity
    ) : Handler() {
        private val mMainActivityWeakReference: WeakReference<MainActivity>

        override fun handleMessage(msg: Message) {
            val mainActivity = mMainActivityWeakReference.get()
            if(mainActivity!=null){
                mainActivity.refreshDisplayAndSetNextUpdate()
            }
        }
        init {
            mMainActivityWeakReference = WeakReference(reference)
        }

    }
}

