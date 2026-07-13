package com.vayunmathur.education.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.Band
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * Band dispatchers. The same content spine is rendered by a different
 * "shell" per band. Home and Course are the divergent entry surfaces; deeper
 * screens (Unit/Lesson/Quiz/Results) are shared.
 *
 * K-2 (Guided Playground) is not built yet, so it falls back to the friendlier
 * Explorer shell for now.
 */
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    when (activeBand(viewModel)) {
        Band.SCHOLAR -> ScholarHomePage(backStack, viewModel)
        else -> ExplorerHomePage(backStack, viewModel)
    }
}

@Composable
fun CoursePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, courseId: String) {
    when (activeBand(viewModel)) {
        Band.SCHOLAR -> ScholarCoursePage(backStack, viewModel, courseId)
        else -> ExplorerCoursePage(backStack, viewModel, courseId)
    }
}

@Composable
private fun activeBand(viewModel: EducationViewModel): Band {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    return viewModel.bandOf(learner)
}
