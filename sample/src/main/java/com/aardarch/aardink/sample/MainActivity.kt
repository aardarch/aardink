package com.aardarch.aardink.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.aardarch.editor.core.PlainTextTokenizer
import com.aardarch.editor.core.rememberCodeEditorState
import com.aardarch.editor.ui.CodeEditorLayout

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val state = rememberCodeEditorState(
                        initialText = SAMPLE_TEXT,
                        tokenizer = PlainTextTokenizer,
                    )
                    CodeEditorLayout(
                        state = state,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
        }
    }
}

private val SAMPLE_TEXT = """
fun greet(name: String): String {
    return "Hello, ${'$'}name!"
}

fun main() {
    println(greet("AardInk"))
}
""".trimIndent()
