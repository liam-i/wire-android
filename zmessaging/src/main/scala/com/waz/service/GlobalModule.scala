/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service

import android.content.{Context => AContext}
import com.softwaremill.macwire._
import com.waz.PermissionsService
import com.waz.api.ZmsVersion
import com.waz.bitmap.BitmapDecoder
import com.waz.bitmap.video.VideoTranscoder
import com.waz.cache.CacheService
import com.waz.client.RegistrationClient
import com.waz.content._
import com.waz.service.assets.{AudioTranscoder, GlobalRecordAndPlayService}
import com.waz.service.call.{Avs, AvsImpl}
import com.waz.service.downloads._
import com.waz.service.images.{ImageLoader, ImageLoaderImpl}
import com.waz.sync.client.{AssetClient, VersionBlacklistClient}
import com.waz.ui.MemoryImageCache
import com.waz.ui.MemoryImageCache.{Entry, Key}
import com.waz.utils.Cache
import com.waz.utils.wrappers.{Context, GoogleApi, GoogleApiImpl}
import com.waz.znet._

import scala.concurrent.Future


class GlobalModule(val context: AContext, val backend: BackendConfig) { global =>
  val prefs:                    GlobalPreferences                = GlobalPreferences(context)
  //trigger initialization of Firebase in onCreate - should prevent problems with Firebase setup
  val googleApi:                GoogleApi                        = new GoogleApiImpl(context, backend, prefs)

  lazy val contextWrapper:      Context                          = Context.wrap(context)
  lazy val storage:             Database                         = new GlobalDatabase(context)
  lazy val metadata:            MetaDataService                  = wire[MetaDataService]
  lazy val cache:               CacheService                     = CacheService(context, storage)
  lazy val bitmapDecoder:       BitmapDecoder                    = wire[BitmapDecoder]

  lazy val trimmingLruCache:    Cache[Key, Entry]                = MemoryImageCache.newTrimmingLru(context)
  lazy val imageCache:          MemoryImageCache                 = wire[MemoryImageCache]

  lazy val network                                               = wire[DefaultNetworkModeService]
  lazy val phoneNumbers:        PhoneNumberService               = wire[PhoneNumberService]
  lazy val timeouts                                              = wire[Timeouts]
  lazy val permissions:         PermissionsService               = wire[PermissionsService]
  lazy val avs:                 Avs                              = wire[AvsImpl]

  lazy val reporting                                             = wire[GlobalReportingService]

  lazy val decoder                                               = Response.CacheResponseBodyDecoder(cache)
  lazy val loginClient                                           = wire[LoginClient]
  lazy val regClient:           RegistrationClient               = wire[RegistrationClient]

  //Not to be used in zms instances
  lazy val globalAssetClient:   AssetClient                      = AssetClient(new ZNetClient(this, "", ""))
  lazy val globalLoader:        AssetLoader                      = wire[AssetLoaderImpl]
  //end of warning...

  lazy val tempFiles:           TempFileService                  = wire[TempFileService]
  lazy val videoTranscoder:     VideoTranscoder                  = VideoTranscoder(context)
  lazy val audioTranscoder:     AudioTranscoder                  = wire[AudioTranscoder]
  lazy val loaderService:       AssetLoaderService               = wire[AssetLoaderService]

  lazy val cacheCleanup                                          = wire[CacheCleaningService]

  lazy val accountsStorage                                       = wire[AccountsStorageImpl]
  lazy val teamsStorage                                          = wire[TeamsStorageImpl]
  lazy val recordingAndPlayback                                  = wire[GlobalRecordAndPlayService]

  lazy val clientWrapper:       Future[ClientWrapper]            = ClientWrapper()
  lazy val client:              AsyncClientImpl                  = new AsyncClientImpl(decoder, AsyncClient.userAgent(metadata.appVersion.toString, ZmsVersion.ZMS_VERSION), clientWrapper)

  lazy val globalClient                                          = new ZNetClient(global, "", "")
  lazy val imageLoader:         ImageLoader                      = new ImageLoaderImpl(context, cache, imageCache, bitmapDecoder, permissions, loaderService, globalLoader) { override def tag = "Global" }

  lazy val blacklistClient                                       = new VersionBlacklistClient(globalClient, backend)
  lazy val blacklist                                             = new VersionBlacklistService(metadata, prefs, blacklistClient)

  lazy val factory                                               = new ZMessagingFactory(this)

  val lifecycle: ZmsLifecycle = new ZmsLifecycleImpl()
}
