package com.example.mobile_connect_android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobile_connect_android.domain.AppModel

@Composable
fun LoginScreen(model: AppModel) {
    val initialBaseUrl by model.baseUrl.collectAsState()
    var serverUrl by remember(initialBaseUrl) { mutableStateOf(initialBaseUrl.ifEmpty { "https://nexlitflow.com/mobile-code-connect" }) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    fun performSignIn() {
        if (busy || serverUrl.isEmpty() || password.isEmpty() || code.length < 6) return
        busy = true
        errorMsg = null
        model.signIn(serverUrl, password, code) { err ->
            busy = false
            if (err != null) {
                errorMsg = err
                code = ""
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                BrandHeader(showsMark = true)
                Spacer(modifier = Modifier.height(20.dp))

                // Card container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    // Server URL Field
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Server URL", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)) },
                        leadingIcon = { Text("🌐", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f))

                    // Password Field
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)) },
                        leadingIcon = { Text("🔒", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f))

                    // 2FA Code Field
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            if (it.length <= 6) {
                                code = it
                                if (it.length == 6 && password.isNotEmpty() && serverUrl.isNotEmpty()) {
                                    performSignIn()
                                }
                            }
                        },
                        label = { Text("6-digit code", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)) },
                        leadingIcon = { Text("#️⃣", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            performSignIn()
                        }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorMsg != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMsg!!,
                        color = Color(0xFFFF6B6B),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                val canSubmit = serverUrl.isNotEmpty() && password.isNotEmpty() && code.length >= 6 && !busy

                Button(
                    onClick = { performSignIn() },
                    enabled = canSubmit,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.height(24.dp).width(24.dp))
                    } else {
                        Text("Sign In", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text(
                text = "Powered by ${Brand.company}",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}
