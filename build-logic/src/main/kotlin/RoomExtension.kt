import gradle.kotlin.dsl.accessors._50084b53781b2d5f3340afb1e969649a.implementation
import gradle.kotlin.dsl.accessors._b80debccebb9ece8e914e516f0647706.ksp
import org.gradle.kotlin.dsl.DependencyHandlerScope

fun DependencyHandlerScope.implementRoom(libs: org.gradle.accessors.dm.LibrariesForLibs) {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
