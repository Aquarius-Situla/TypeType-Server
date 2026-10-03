plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly("com.github.TeamNewPipe:nanojson:1d9e1aea9049fc9f85e68b43ba39fe7be1c1f751")
    implementation("com.github.Priveetee.PipePipeExtractor:extractor:6758a016490f5c96e4f98154a47bc1f9a9ad251d")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
