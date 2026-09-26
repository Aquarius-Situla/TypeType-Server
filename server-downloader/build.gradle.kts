plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly("com.github.TeamNewPipe:nanojson:1d9e1aea9049fc9f85e68b43ba39fe7be1c1f751")
    implementation("com.github.Priveetee.PipePipeExtractor:extractor:4a4e48ff762ea60cf2915adf57ec6d94bd86f2a4")
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
