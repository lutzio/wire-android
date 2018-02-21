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
import com.waz.api.{NetworkMode, Verification}
import com.waz.model.UserData
import com.waz.service.ZMessaging
import com.waz.utils.events.{Signal, Subscription}
import com.waz.utils.returning
import com.waz.zclient.common.controllers.ThemeController
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.common.views.UserDetailsView
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.conversation.controller.IConversationScreenController
import com.waz.zclient.participants.ParticipantsController
import com.waz.zclient.ui.text.AccentColorEditText
import com.waz.zclient.ui.utils.KeyboardUtils
import com.waz.zclient.utils.{RichView, ViewUtils}
import com.waz.zclient.views.e2ee.ShieldView
import com.waz.zclient.{FragmentHelper, R}

class ParticipantHeaderFragment extends BaseFragment[ParticipantHeaderFragment.Container] with FragmentHelper {
  import com.waz.threading.Threading.Implicits.Ui

  private lazy val participantsController = inject[ParticipantsController]
  private lazy val convScreenController   = inject[IConversationScreenController]

  private val editInProgress = Signal(false)

  private lazy val internetAvailable = for {
    z           <- inject[Signal[ZMessaging]]
    networkMode <- z.network.networkMode
  } yield networkMode != NetworkMode.OFFLINE && networkMode != NetworkMode.UNKNOWN

  private lazy val editAllowed = for {
    isSingleParticipant <- participantsController.otherParticipant.map(_.isDefined)
    edit                <- editInProgress
  } yield !isSingleParticipant && !edit

  private var subs = Set.empty[Subscription]

  private lazy val toolbar = view[Toolbar](R.id.t__participants__toolbar)

  private lazy val membersCountTextView = returning(view[TextView](R.id.ttv__participants__sub_header)) { view =>
    participantsController.otherParticipants.map(_.size) { participants =>
      view.foreach { mc =>
        mc.setText(getResources.getQuantityString(R.plurals.participants__sub_header_xx_people, participants, participants.asInstanceOf[java.lang.Integer]))
        mc.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources.getDimension(R.dimen.wire__text_size__small))
      }
    }

    (for {
      isSingleParticipant <- participantsController.otherParticipant.map(_.isDefined)
      edit                <- editInProgress
    } yield !isSingleParticipant && !edit){ isVisible =>
      view.foreach(_.setVisible(isVisible))
    }
  }

  private lazy val userDetailsView = returning(view[UserDetailsView](R.id.udv__participants__user_details)) { view =>
    participantsController.otherParticipant.collect { case Some(userId) => userId } { userId =>
      view.foreach(_.setUserId(userId))
    }

    (for {
      isSingleParticipant <- participantsController.otherParticipant.map(_.isDefined)
      edit                <- editInProgress
    } yield isSingleParticipant && !edit){ isVisible =>
      view.foreach(_.setVisible(isVisible))
    }
  }

  private lazy val headerEditText = returning(view[AccentColorEditText](R.id.taet__participants__header__editable)) { view =>
    (for {
      true        <- inject[ThemeController].darkThemeSet
      accentColor <- inject[AccentColorController].accentColor
    } yield accentColor) { color =>
      view.foreach(_.setAccentColor(color.getColor))
    }

    editInProgress {
      case true => view.foreach { he =>
        he.requestFocus()
        he.setVisible(true)
        headerReadOnlyTextView.foreach { hr =>
          he.setText(hr.getText)
          he.setSelection(hr.getText.length)
        }
      }
      case false => view.foreach { he =>
        he.clearFocus()
        he.requestLayout()
        he.setVisible(false)
      }
    }
  }

  private lazy val headerReadOnlyTextView = returning(view[TextView](R.id.ttv__participants__header)) { view =>
    (for {
      convName <- participantsController.conv.map(_.displayName)
      userId   <- participantsController.otherParticipant
      user     <- userId.fold(Signal.const(Option.empty[UserData]))(id => Signal.future(participantsController.getUser(id)))
    } yield user.fold(convName)(_.name)) { name =>
      view.foreach(_.setText(name))
    }

    editInProgress { edit =>
      view.foreach(_.setVisible(!edit))
    }
  }

  private lazy val penIcon = returning(view[TextView](R.id.gtv__participants_header__pen_icon)) { view =>
    editAllowed.onUi { edit =>
      view.foreach { pi => pi.setVisible(edit) }
    }
  }

  private lazy val shieldView = returning(view[ShieldView](R.id.verified_shield)) { view =>
    (for {
      isGroupOrBot <- participantsController.isGroupOrBot
      user         <- if (isGroupOrBot) Signal.const(Option.empty[UserData])
      else participantsController.otherParticipant.flatMap {
        case Some(userId) => Signal.future(participantsController.getUser(userId))
        case None         => Signal.const(Option.empty[UserData])
      }
    } yield user.fold(false)(_.verified == Verification.VERIFIED)) { isVerified =>
      view.foreach(_.setVisible(isVerified))
    }
  }

  private def renameConversation() = internetAvailable.head.foreach {
    case true =>
      headerEditText.foreach { he =>
        val text = he.getText.toString.trim
        inject[ConversationController].setCurrentConvName(text)
        headerReadOnlyTextView.foreach(_.setText(text))
      }
    case false =>
      participantsController.conv.map(_.displayName).head.foreach { name =>
        headerReadOnlyTextView.foreach(_.setText(name))
      }
      showOfflineRenameError()
  }

  private val editorActionListener: TextView.OnEditorActionListener = new TextView.OnEditorActionListener() {
    override def onEditorAction(textView: TextView, actionId: Int, event: KeyEvent): Boolean =
      if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode == KeyEvent.KEYCODE_ENTER)) {
        renameConversation()

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
      override def onClick(v: View): Unit = editInProgress.head.foreach {
        case true =>
          renameConversation()
          editInProgress ! false
        case false =>
          participantsController.otherParticipant.map(_.isDefined).head.foreach {
            case true =>
              convScreenController.hideUser()
              participantsController.unselectParticipant()
            case false =>
              convScreenController.hideParticipants(true, false)
              getActivity.onBackPressed()
          }
      }
    }))

    membersCountTextView
    userDetailsView
    shieldView
    headerEditText.foreach { _.setOnEditorActionListener(editorActionListener) }
    headerReadOnlyTextView.foreach(_.onClick { triggerEditingOfConversationNameIfInternet() })
    penIcon.foreach(_.onClick { triggerEditingOfConversationNameIfInternet() })

    subs += editInProgress {
      case true =>
        convScreenController.editConversationName(true)
        KeyboardUtils.showKeyboard(getActivity)
      case false =>
        convScreenController.editConversationName(false)
        KeyboardUtils.hideKeyboard(getActivity)
    }

  }

  override def onDestroyView(): Unit = {
    subs.foreach(_.destroy())
    subs = Set.empty

    super.onDestroyView()
  }
}

object ParticipantHeaderFragment {
  val TAG: String = classOf[ParticipantHeaderFragment].getName

  def newInstance: ParticipantHeaderFragment = new ParticipantHeaderFragment

  trait Container {
  }
}
