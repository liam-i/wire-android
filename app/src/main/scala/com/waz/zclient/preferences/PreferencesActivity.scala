/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.preferences

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.content.{Context, Intent}
import android.media.RingtoneManager
import android.net.Uri
import android.os.{Build, Bundle}
import android.support.annotation.Nullable
import android.support.v4.app.{Fragment, FragmentManager, FragmentTransaction}
import android.support.v7.widget.Toolbar
import android.view.{MenuItem, View, ViewGroup}
import android.widget._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.ImageAsset
import com.waz.content.UserPreferences
import com.waz.service.{AccountsService, ZMessaging}
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.zclient.Intents._
import com.waz.zclient.SpinnerController.{Hide, Show}
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.common.views.AccountTabsView
import com.waz.zclient.pages.main.profile.camera.{CameraContext, CameraFragment}
import com.waz.zclient.preferences.pages.{DevicesBackStackKey, OptionsView, ProfileBackStackKey}
import com.waz.zclient.utils.{BackStackNavigator, RingtoneUtils, ViewUtils}
import com.waz.zclient.{ActivityHelper, BaseActivity, R}
import com.waz.zclient.views.LoadingIndicatorView
import com.waz.zclient._

class PreferencesActivity extends BaseActivity
  with ActivityHelper
  with CameraFragment.Container {

  import PreferencesActivity._

  private lazy val toolbar     = findById[Toolbar](R.id.toolbar)
  private lazy val accountTabs = findById[AccountTabsView](R.id.account_tabs)
  private lazy val accountTabsContainer = findById[FrameLayout](R.id.account_tabs_container)

  private lazy val backStackNavigator = inject[BackStackNavigator]
  private lazy val zms = inject[Signal[ZMessaging]]
  private lazy val spinnerController = inject[SpinnerController]

  lazy val accentColor = inject[AccentColorController].accentColor
  lazy val accounts = inject[AccountsService]

  @SuppressLint(Array("PrivateResource"))
  override def onCreate(@Nullable savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    setSupportActionBar(toolbar)
    getSupportActionBar.setDisplayHomeAsUpEnabled(true)
    getSupportActionBar.setDisplayShowHomeEnabled(true)

    ViewUtils.lockScreenOrientation(Configuration.ORIENTATION_PORTRAIT, this)
    if (savedInstanceState == null) {
      backStackNavigator.setup(findViewById(R.id.content).asInstanceOf[ViewGroup])

      getIntent.page match {
        case Some(Page.Devices) => backStackNavigator.goTo(DevicesBackStackKey())
        case _                  => backStackNavigator.goTo(ProfileBackStackKey())
      }

      Signal(backStackNavigator.currentState, ZMessaging.currentAccounts.accountsWithManagers.map(_.toSeq.length)).on(Threading.Ui){
        case (state: ProfileBackStackKey, c) if c > 1 =>
          setTitle(R.string.empty_string)
          accountTabsContainer.setVisibility(View.VISIBLE)
        case (state, _) =>
          setTitle(state.nameId)
          accountTabsContainer.setVisibility(View.GONE)
      }
    } else {
      backStackNavigator.onRestore(findViewById(R.id.content).asInstanceOf[ViewGroup], savedInstanceState)
    }

    accentColor.on(Threading.Ui) { color =>
      getControllerFactory.getUserPreferencesController.setLastAccentColor(color.getColor())
      getControllerFactory.getAccentColorController.setColor(color.getColor())
    }

    accountTabs.onTabClick.onUi { account =>
      val intent = new Intent()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        intent.putExtra(SwitchAccountExtra, account.id.str)
        setResult(Activity.RESULT_OK, intent)
      } else {
        ZMessaging.currentAccounts.setAccount(Some(account.id))
        setResult(Activity.RESULT_CANCELED, intent)
      }
      finish()
    }

    accounts.activeAccountId.map(_.isEmpty).onUi {
      case true => finish()
      case _ =>
    }

    val loadingIndicator = findViewById[LoadingIndicatorView](R.id.progress_spinner)

    spinnerController.spinnerShowing.onUi {
      case Show(animation, forcedTheme) => loadingIndicator.show(animation, darkTheme = forcedTheme.getOrElse(true))
      case Hide(Some(message)) => loadingIndicator.hideWithMessage(message, 1000)
      case _ => loadingIndicator.hide()
    }
  }


  override def onSaveInstanceState(outState: Bundle) = {
    super.onSaveInstanceState(outState)
    backStackNavigator.onSaveState(outState)
  }

  override def onStart(): Unit = {
    super.onStart()
    getControllerFactory.getCameraController.addCameraActionObserver(this)
  }

  override def onStop(): Unit = {
    super.onStop()
    getControllerFactory.getCameraController.removeCameraActionObserver(this)
  }


  override def getBaseTheme: Int = R.style.Theme_Dark_Preferences

  override protected def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) = {
    super.onActivityResult(requestCode, resultCode, data)
    val fragment: Fragment = getSupportFragmentManager.findFragmentById(R.id.fl__root__camera)
    if (fragment != null) fragment.onActivityResult(requestCode, resultCode, data)

    if (resultCode == Activity.RESULT_OK && Seq(OptionsView.RingToneResultId, OptionsView.TextToneResultId, OptionsView.PingToneResultId).contains(requestCode)) {

      val pickedUri = Option(data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI).asInstanceOf[Uri])
      val key = requestCode match {
        case OptionsView.RingToneResultId => UserPreferences.RingTone
        case OptionsView.TextToneResultId => UserPreferences.TextTone
        case OptionsView.PingToneResultId => UserPreferences.PingTone
      }
      zms.head.flatMap(_.userPrefs.preference(key).update(pickedUri.fold(RingtoneUtils.getSilentValue)(_.toString)))(Threading.Ui)
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case android.R.id.home =>
        onBackPressed()
      case _ =>
    }
    super.onOptionsItemSelected(item)
  }


  override def onBackPressed() = {
    Option(getSupportFragmentManager.findFragmentByTag(CameraFragment.TAG).asInstanceOf[CameraFragment]).fold{
      if (!spinnerController.spinnerShowing.currentValue.exists(_.isInstanceOf[Show]) && !backStackNavigator.back())
        finish()
    }{ _.onBackPressed() }
  }

  //TODO do we need to check internet connectivity here?
  override def onBitmapSelected(imageAsset: ImageAsset, imageFromCamera: Boolean, cameraContext: CameraContext) =
    if (cameraContext == CameraContext.SETTINGS) {
      inject[Signal[ZMessaging]].head.map { zms =>
        zms.users.updateSelfPicture(imageAsset)
      } (Threading.Background)
      getSupportFragmentManager.popBackStack(CameraFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

  override def onCameraNotAvailable() =
    Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()

  override def onOpenCamera(cameraContext: CameraContext) = {
    Option(getSupportFragmentManager.findFragmentByTag(CameraFragment.TAG)) match {
      case None =>
        getSupportFragmentManager
          .beginTransaction
          .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
          .add(R.id.fl__root__camera, CameraFragment.newInstance(cameraContext), CameraFragment.TAG)
          .addToBackStack(CameraFragment.TAG)
          .commit
      case Some(_) => //do nothing
    }
  }

  def onCloseCamera(cameraContext: CameraContext) =
    getSupportFragmentManager.popBackStack(CameraFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)

}

object PreferencesActivity {
  val SwitchAccountCode = 789
  val SwitchAccountExtra = "SWITCH_ACCOUNT_EXTRA"

  def getDefaultIntent(context: Context): Intent =
    new Intent(context, classOf[PreferencesActivity])
}
