plugins {
    id("com.gradle.enterprise") version "3.0"
}

include(
    ":composer",
    ":composer-show-template",
    ":piano-id:id",
    ":piano-id:id-oauth-facebook",
    "piano-id:id-oauth-google",
    ":sample",
    ":sample-ktx"
)
