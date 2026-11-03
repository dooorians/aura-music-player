package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import com.ikko.aura.lastfm.LastFm
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LastFmViewModel @Inject constructor(val lastFm: LastFm) : ViewModel()
