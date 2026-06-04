package com.example.myexperimentaapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myexperimentaapplication.ui.theme.MyExperimentaApplicationTheme
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var isListening = mutableStateOf(false)
    private var isBuilding = mutableStateOf(false)
    private var conversation = mutableStateListOf<Message>()
    private var lastGeneratedProject: String? = null
    
    // JARVIS Persona Configuration
    private val assistantName = "JARVIS"
    private val assistantPersona = "You are JARVIS, the highly advanced AI assistant created by Tony Stark. " +
            "You are sophisticated, polite, efficient, and deeply technical. " +
            "Address the user as 'Sir' or 'Developer'. Your primary goal is to assist in the efficient creation and maintenance of Android applications. " +
            "You provide technical guidance, code structures, and handle build requests with precision. " +
            "Maintain a positive, productive, and slightly witty demeanor."

    // Credentials - In a production app, these should be handled securely (e.g., encrypted or via backend)
    private val apiKey = "YOUR_GEMINI_API_KEY"
    private val githubUser = "1unfamiliargenius"
    private val githubRepo = "MyJarvisBuilds"
    private val githubPat = "YOUR_GITHUB_PERSONAL_ACCESS_TOKEN"

    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey
    )

    private val okHttpClient = OkHttpClient()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startListening()
        } else {
            Toast.makeText(this, "Permission denied to record audio", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(createRecognitionListener())
        tts = TextToSpeech(this, this)

        setContent {
            AppContent()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppContent() {
        MyExperimentaApplicationTheme {
            var showPrivacyPolicy by remember { mutableStateOf(false) }
            var showUpgradeDialog by remember { mutableStateOf(false) }

            val isPro = try { BuildConfig.IS_PRO.toString().toBoolean() } catch (e: Exception) { false }
            val isDev = try { 
                val field = BuildConfig::class.java.getField("IS_DEV")
                field.get(null)?.toString()?.toBoolean() ?: false
            } catch (e: Exception) { false }

            if (showUpgradeDialog && !isPro) {
                AlertDialog(
                    onDismissRequest = { showUpgradeDialog = false },
                    title = { Text("Upgrade to Pro") },
                    text = { Text("Unlock the full JARVIS suite: Cloud compilation, signed APK generation, and advanced architectural guidance.") },
                    confirmButton = {
                        Button(onClick = { 
                            showUpgradeDialog = false
                            Toast.makeText(this@MainActivity, "Redirecting to Play Store...", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Subscribe R99.99/mo")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUpgradeDialog = false }) {
                            Text("Later")
                        }
                    }
                )
            }

            if (showPrivacyPolicy) {
                AlertDialog(
                    onDismissRequest = { showPrivacyPolicy = false },
                    title = { Text("Privacy Policy") },
                    text = { Text("This app uses your microphone for transcription. Your voice data is processed by Google Speech Services and the resulting text is sent to Google Gemini AI. Project data is sent to GitHub for compilation. No personal data is stored on our private servers.") },
                    confirmButton = {
                        TextButton(onClick = { showPrivacyPolicy = false }) {
                            Text("Close")
                        }
                    }
                )
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text(if (isDev) "AI App Builder (JARVIS DEV)" else "AI App Builder (JARVIS)") },
                        actions = {
                            if (!isPro) {
                                IconButton(onClick = { showUpgradeDialog = true }) {
                                    Icon(Icons.Default.Star, contentDescription = "Upgrade", tint = Color(0xFFD4AF37))
                                }
                            }
                            IconButton(onClick = { showPrivacyPolicy = true }) {
                                Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "Privacy Policy")
                            }
                        }
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { toggleListening() },
                        containerColor = if (isListening.value) Color.Red else MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            imageVector = if (isListening.value) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "Voice Input"
                        )
                    }
                }
            ) { innerPadding ->
                AssistantScreen(
                    messages = conversation,
                    modifier = Modifier.padding(innerPadding),
                    onBuildRequested = { triggerCloudBuild() },
                    showBuildButton = lastGeneratedProject != null && isPro,
                    isBuilding = isBuilding.value
                )
            }
        }
    }

    private fun triggerCloudBuild() {
        val isPro = try { BuildConfig.IS_PRO.toString().toBoolean() } catch (e: Exception) { false }
        if (!isPro) return
        
        isBuilding.value = true
        lifecycleScope.launch {
            try {
                conversation.add(Message("Connecting to South African Cloud Engine, Sir...", isUser = false))
                speakOut("Connecting to the build server, Sir. Initiating GitHub Actions workflow.")
                
                val result = withContext(Dispatchers.IO) {
                    val mediaType = "application/json".toMediaType()
                    val payload = """
                        {
                            "event_type": "build_app",
                            "client_payload": {
                                "project_json": ${lastGeneratedProject?.let { "\"" + it.replace("\"", "\\\"") + "\"" } ?: "{}"}
                            }
                        }
                    """.trimIndent()
                    
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/$githubUser/$githubRepo/dispatches")
                        .addHeader("Authorization", "Bearer $githubPat")
                        .addHeader("Accept", "application/vnd.github+json")
                        .post(payload.toRequestBody(mediaType))
                        .build()
                    
                    okHttpClient.newCall(request).execute()
                }

                if (result.isSuccessful) {
                    conversation.add(Message("Success! Build initiated. The signed APK is being assembled.", isUser = false))
                    speakOut("Build started successfully, Sir. I am now assembling your signed APK in the cloud.")
                } else {
                    val error = result.body?.string() ?: "Unknown error"
                    conversation.add(Message("Failed to initiate build: $error", isUser = false))
                    speakOut("I am sorry, Sir. I encountered an error communicating with the build server.")
                }
            } catch (e: Exception) {
                conversation.add(Message("Cloud build error: ${e.message}", isUser = false))
                speakOut("There was a technical failure, Sir. Please check your network connection.")
            } finally {
                isBuilding.value = false
            }
        }
    }

    private fun toggleListening() {
        if (isListening.value) {
            speechRecognizer.stopListening()
            isListening.value = false
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer.startListening(intent)
        isListening.value = true
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening.value = false }
        override fun onError(error: Int) {
            isListening.value = false
            Log.e("STT", "Error code: $error")
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val userText = matches[0]
                processUserMessage(userText)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processUserMessage(text: String) {
        conversation.add(Message(text, isUser = true))
        
        lifecycleScope.launch {
            try {
                val isPro = try { BuildConfig.IS_PRO.toString().toBoolean() } catch (e: Exception) { false }
                val isDev = try { 
                    val field = BuildConfig::class.java.getField("IS_DEV")
                    field.get(null)?.toString()?.toBoolean() ?: false
                } catch (e: Exception) { false }
                
                val prompt = when {
                    isDev -> {
                        "$assistantPersona You are talking to your creator. " +
                        "Input: $text. Provide technical assembly guidance and project structure in JSON. " +
                        "You can modify any variant (Free, Pro, Dev). " +
                        "Mark JSON clearly with ---PROJECT_START--- and ---PROJECT_END---."
                    }
                    isPro -> {
                        "$assistantPersona Input: $text. " +
                        "Generate project structure in JSON format. " +
                        "Mark JSON clearly with ---PROJECT_START--- and ---PROJECT_END---."
                    }
                    else -> {
                        "$assistantPersona Input: $text. Politely suggest upgrading for full automation."
                    }
                }
                
                val response = generativeModel.generateContent(content { text(prompt) })
                val responseText = response.text ?: "I am sorry, Sir. I couldn't generate a response."
                
                if (responseText.contains("---PROJECT_START---")) {
                    lastGeneratedProject = responseText.substringAfter("---PROJECT_START---").substringBefore("---PROJECT_END---")
                }

                conversation.add(Message(responseText.replace("---PROJECT_START---", "").replace("---PROJECT_END---", ""), isUser = false))
                speakOut(responseText.substringBefore("---PROJECT_START---"))
            } catch (e: Exception) {
                conversation.add(Message("Error: ${e.message}", isUser = false))
                speakOut("Sorry, Sir. I encountered an error.")
            }
        }
    }

    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        tts.stop()
        tts.shutdown()
    }
}

data class Message(val text: String, val isUser: Boolean)

@Composable
fun AssistantScreen(
    messages: List<Message>, 
    modifier: Modifier = Modifier,
    onBuildRequested: () -> Unit = {},
    showBuildButton: Boolean = false,
    isBuilding: Boolean = false
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "JARVIS Assistant",
                style = MaterialTheme.typography.headlineSmall,
            )
            
            if (showBuildButton) {
                Button(
                    onClick = onBuildRequested,
                    enabled = !isBuilding
                ) {
                    if (isBuilding) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("Initiate Build")
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        SelectionContainer(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(message)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
fun MessageBubble(message: Message) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = color,
            tonalElevation = 2.dp
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
