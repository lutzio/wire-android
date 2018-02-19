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
package com.waz.zclient.participants.fragments

import android.content.Context
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.Toolbar
import android.util.TypedValue
import android.view._
import android.view.animation.{AlphaAnimation, Animation}
import android.view.inputmethod.{EditorInfo, InputMethodManager}
import android.widget.TextView
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.verbose
import com.waz.api.{NetworkMode, Verification}
import com.waz.model.UserData
import com.waz.service.ZMessaging
import com.waz.utils.events.{EventStream, Signal, Subscription}
import com.waz.utils.returning
import com.waz.zclient.common.controllers.ThemeController
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.common.views.UserDetailsView
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.participants.ParticipantsController
import com.waz.zclient.ui.text.AccentColorEditText
import com.waz.zclient.ui.utils.KeyboardUtils
import com.waz.zclient.utils.{RichView, ViewUtils}
import com.waz.zclient.views.e2ee.ShieldView
import com.waz.zclient.{FragmentHelper, R}

class ParticipantsHeaderFragment extends BaseFragment[ParticipantsHeaderFragment.Container] with FragmentHelper {

  import com.waz.threading.Threading.Implicits.Ui

  private lazy val toolbar = view[Toolbar](R.id.t__participants__toolbar)

  private lazy val membersCountTextView = returning(view[TextView](R.id.ttv__participants__sub_header)) { view =>
    participantsController.otherParticipants.map(_.size) { participants =>
      view.foreach { mc =>
        verbose(s"PF members counter, other participants set to $participants")
        mc.setText(getResources.getQuantityString(R.plurals.participants__sub_header_xx_people, participants, participants.asInstanceOf[java.lang.Integer]))
        mc.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources.getDimension(R.dimen.wire__text_size__small))
      }
    }

    (for {
      isSingleParticipant <- participantsController.otherParticipant.map(_.isDefined)
      edit <- editInProgress
    } yield !isSingleParticipant && !edit){ isVisible =>
      verbose(s"PF members counter, setting visibility to $isVisible")
      view.foreach(_.setVisible(isVisible))
    }
  }

  private lazy val userDetailsView = returning(view[UserDetailsView](R.id.udv__participants__user_details)) { view =>
    participantsController.otherParticipant.collect { case Some(userId) => userId } { userId =>
      verbose(s"PF user details, setting a single userId: $userId")
      view.foreach(_.setUserId(userId))
    }

    (for {
      isSingleParticipant <- participantsController.otherParticipant.map(_.isDefined)
      edit <- editInProgress
    } yield isSingleParticipant && !edit){ isVisible =>
      verbose(s"PF user details, setting visibility to $isVisible")
      view.foreach(_.setVisible(isVisible))
    }
  }

  private lazy val headerEditText = returning(view[AccentColorEditText](R.id.taet__participants__header__editable)) { view =>
    (for {
      true        <- themeController.darkThemeSet
      accentColor <- accentColorController.accentColor
    } yield accentColor) { color =>
      verbose(s"PF header edit, color: $color")
      view.foreach(_.setAccentColor(color.getColor))
    }

    editInProgress {
      case true => view.foreach { he =>
        he.requestFocus()
        he.setVisible(true)
        headerReadOnlyTextView.foreach { hr =>
          verbose(s"PF header edit  (true), setting the text to ${hr.getText}")
          he.setText(hr.getText)
          he.setSelection(hr.getText.length)
        }
      }
      case false => view.foreach { he =>
        verbose(s"PF header edit (false)")
        he.clearFocus()
        he.requestLayout()
        he.setVisible(false)
      }
    }
  }

  private lazy val headerReadOnlyTextView = returning(view[TextView](R.id.ttv__participants__header)) { view =>
    (for {
      convName <- convController.currentConv.map(_.displayName)
      _ = verbose(s"PF conv name: $convName")
      userId   <- participantsController.otherParticipant
      _ = verbose(s"PF userId: $userId")
      user     <- userId.fold(Signal.const(Option.empty[UserData]))(id => Signal.future(participantsController.getUser(id)))
      _ = verbose(s"PF user name: ${user.map(_.name)}")
    } yield user.fold(convName)(_.name)) { name =>
      view.foreach(_.setText(name))
    }

    editInProgress { edit =>
      verbose(s"PF header readonly set visible: ${!edit}")
      view.foreach(_.setVisible(!edit))
    }
  }

  private lazy val penIcon = returning(view[TextView](R.id.gtv__participants_header__pen_icon)) { view =>
    editAllowed.onUi { edit =>
      verbose(s"PF pen icon, visible if not a singleParticipant and not edit: $edit")
      view.foreach { pi => pi.setVisible(edit) }
    }
  }

  private lazy val shieldView = returning(view[ShieldView](R.id.sv__otr__verified_shield)) { view =>
    (for {
      isGroupOrBot <- participantsController.isGroupOrBot
      user         <- if (isGroupOrBot) Signal.const(Option.empty[UserData])
      else participantsController.otherParticipant.flatMap {
        case Some(userId) => Signal.future(participantsController.getUser(userId))
        case None         => Signal.const(Option.empty[UserData])
      }
    } yield user.fold(false)(_.verified == Verification.VERIFIED)) { isVerified =>
      verbose(s"PF shield view: $isVerified")
      view.foreach(_.setVisible(isVerified))
    }
  }

  private lazy val convController         = inject[ConversationController]
  private lazy val participantsController = inject[ParticipantsController]
  private lazy val themeController        = inject[ThemeController]
  private lazy val accentColorController  = inject[AccentColorController]

  private lazy val internetAvailable = for {
    z           <- inject[Signal[ZMessaging]]
    networkMode <- z.network.networkMode
  } yield networkMode != NetworkMode.OFFLINE && networkMode != NetworkMode.UNKNOWN

  val onNavigationClicked = EventStream[Unit]()
  val editInProgress = Signal(false)

  private lazy val editAllowed = for {
    isSingleParticipant <- participantsController.otherParticipant.map(_.isDefined)
    _ = verbose(s"PF isSingleParticipant = $isSingleParticipant")
    edit                <- editInProgress
  } yield !isSingleParticipant && !edit

  private val editorActionListener: TextView.OnEditorActionListener = new TextView.OnEditorActionListener() {
    override def onEditorAction(textView: TextView, actionId: Int, event: KeyEvent): Boolean =
      if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode == KeyEvent.KEYCODE_ENTER)) {
        verbose(s"PF conversation rename event")
        internetAvailable.head.foreach {
          case true => headerEditText.foreach { he =>
            val text = he.getText.toString.trim
            convController.setCurrentConvName(text)
            headerReadOnlyTextView.foreach(_.setText(text))
            verbose(s"PF conversation renamed")
          }
          case false => convController.currentConv.map(_.displayName).head.foreach { name =>
            headerReadOnlyTextView.foreach(_.setText(name))
          }
          showOfflineRenameError()
        }

        headerEditText.foreach { he =>
          he.clearFocus()
          getActivity
            .getSystemService(Context.INPUT_METHOD_SERVICE).asInstanceOf[InputMethodManager]
            .hideSoftInputFromWindow(he.getWindowToken, 0)
        }

        editInProgress ! false
        true
      } else false
  }

  private def triggerEditingOfConversationNameIfInternet(): Unit = (for {
    edit     <- editAllowed.head
    internet <- internetAvailable.head
  } yield (edit, internet)).foreach {
    case (true, false) => showOfflineRenameError()
    case (true, true)  => editInProgress ! true
    case _ =>
  }

  private def showOfflineRenameError(): Unit =
    ViewUtils.showAlertDialog(
      getActivity,
      R.string.alert_dialog__no_network__header,
      R.string.rename_conversation__no_network__message,
      R.string.alert_dialog__confirmation,
      null,
      true
    )

  private var subs = Set.empty[Subscription]

  // This is a workaround for the bug where child fragments disappear when
  // the parent is removed (as all children are first removed from the parent)
  // See https://code.google.com/p/android/issues/detail?id=55228
  // Apply the workaround only if this is a child fragment, and the parent is being removed.
  override def onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation = Option(getParentFragment) match {
    case Some(parent: Fragment) if enter && parent.isRemoving => returning(new AlphaAnimation(1, 1)){
      _.setDuration(ViewUtils.getNextAnimationDuration(parent))
    }
    case _ => super.onCreateAnimation(transit, enter, nextAnim)
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    inflater.inflate(R.layout.fragment_participants_header, container, false)
  }

  override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
    super.onViewCreated(view, savedInstanceState)

    toolbar.foreach(_.setNavigationOnClickListener(new View.OnClickListener() {
      override def onClick(v: View): Unit = onNavigationClicked ! Unit
    }))

    membersCountTextView
    userDetailsView

    headerEditText.foreach { _.setOnEditorActionListener(editorActionListener) }

    headerReadOnlyTextView.foreach(_.onClick {
      verbose(s"PF read only on click")
      triggerEditingOfConversationNameIfInternet()
    })

    penIcon.foreach(_.onClick {
      verbose(s"PF pen icon on click")
      triggerEditingOfConversationNameIfInternet()
    })

    shieldView

    subs += onNavigationClicked { _ =>
      verbose(s"PF navigation clicked")
      getControllerFactory.getConversationScreenController.hideParticipants(true, false)
    }

    subs += editInProgress {
      case true =>
        getControllerFactory.getConversationScreenController.editConversationName(true)
        KeyboardUtils.showKeyboard(getActivity)
      case false =>
        getControllerFactory.getConversationScreenController.editConversationName(false)
    }

  }

  override def onDestroyView(): Unit = {
    subs.foreach(_.destroy())
    subs = Set.empty

    super.onDestroyView()
  }

}

object ParticipantsHeaderFragment {
  val TAG: String = classOf[ParticipantsHeaderFragment].getName

  def newInstance: ParticipantsHeaderFragment = new ParticipantsHeaderFragment

  trait Container {
  }
}
