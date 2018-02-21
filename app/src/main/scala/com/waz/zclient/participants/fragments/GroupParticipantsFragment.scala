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
import android.support.annotation.Nullable
import android.support.v7.widget.{GridLayoutManager, RecyclerView}
import android.view.animation.{AlphaAnimation, Animation}
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.LinearLayout
import com.waz.ZLog.ImplicitTag._
import com.waz.api.NetworkMode
import com.waz.model.{UserData, UserId}
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils._
import com.waz.utils.events._
import com.waz.zclient.common.controllers.UserAccountsController
import com.waz.zclient.core.stores.network.NetworkAction
import com.waz.zclient.integrations.IntegrationDetailsController
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.conversation.controller.IConversationScreenController
import com.waz.zclient.pages.main.pickuser.controller.IPickUserController
import com.waz.zclient.participants.{ParticipantsChatheadAdapter, ParticipantsController}
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{UiStorage, UserSignal, ViewUtils}
import com.waz.zclient.views.menus.{FooterMenu, FooterMenuCallback}
import com.waz.zclient.{FragmentHelper, R}

class GroupParticipantsFragment extends BaseFragment[GroupParticipantsFragment.Container] with FragmentHelper {

  implicit def ctx: Context = getActivity
  import Threading.Implicits.Ui

  private lazy val zms                          = inject[Signal[ZMessaging]]
  private lazy val participantsController       = inject[ParticipantsController]
  private lazy val convScreenController         = inject[IConversationScreenController]
  private lazy val userAccountsController       = inject[UserAccountsController]
  private lazy val integrationDetailsController = inject[IntegrationDetailsController]

  private lazy val participantsAdapter = returning(new ParticipantsChatheadAdapter(getInt(R.integer.participant_column__count))) { adapter =>
    new FutureEventStream[UserId, Option[UserData]](adapter.onClick, participantsController.getUser).onUi {
      case Some(user) => (user.providerId, user.integrationId) match {
        case (Some(pId), Some(iId)) =>
          // only team members can remove services from a conversation
          implicit val uiStorage = inject[UiStorage]
          (for {
            z    <- zms.head
            self <- UserSignal(z.selfUserId).head
            conv <- participantsController.conv.head
            isCurrentUserGuest = conv.team.isDefined && conv.team != self.teamId
          } yield (conv, isCurrentUserGuest)).map {
            case (conv, false) =>
              integrationDetailsController.setRemoving(conv.id, user.id)
              convScreenController.showIntegrationDetails(pId, iId)
            case _ =>
          }
        case _ =>
          if (convScreenController.showUser(user.id))
            participantsController.selectParticipant(user.id)
      }
      case _ =>
    }
  }

  private lazy val footerMenuCallback = new FooterMenuCallback() {
    override def onLeftActionClicked(): Unit = {
      (for {
        conv    <- participantsController.conv.head
        isGroup <- participantsController.isGroup.head
      } yield (conv.id, conv.isActive, isGroup)).foreach {
        case (convId, true, true) if userAccountsController.hasAddConversationMemberPermission(convId) =>
          convScreenController.addPeopleToConversation()
        case _ =>
      }
    }

    override def onRightActionClicked(): Unit = getStoreFactory.networkStore.doIfHasInternetOrNotifyUser(new NetworkAction() {
      override def execute(networkMode: NetworkMode): Unit =
        participantsController.conv.head.foreach { conv =>
          if (conv.isActive)
            convScreenController.showConversationMenu(false, conv.id)
        }

      override def onNoNetwork(): Unit = ViewUtils.showAlertDialog(getActivity,
        R.string.alert_dialog__no_network__header, R.string.leave_conversation_failed__message,
        R.string.alert_dialog__confirmation, null, true
      )
    })

  }

  private lazy val participantsView = view[RecyclerView](R.id.pgv__participants)
  private lazy val topBorder        = view[View](R.id.v_participants__footer__top_border)
  private lazy val footerWrapper    = view[LinearLayout](R.id.ll__participants__footer_wrapper)
  private lazy val footerMenu       = returning(view[FooterMenu](R.id.fm__participants__footer)) { fm =>

    val showAddPeople = for {
      conv    <- participantsController.conv
      isGroup <- participantsController.isGroup
    } yield conv.isActive && isGroup && userAccountsController.hasAddConversationMemberPermission(conv.id)

    showAddPeople.map {
      case true  => R.string.glyph__add_people
      case false => R.string.empty_string
    }.onUi { textId =>
      fm.foreach(_.setLeftActionText(getString(textId)))
    }

    showAddPeople.map {
      case true  => R.string.conversation__action__add_people
      case false => R.string.empty_string
    }.onUi { textId =>
      fm.foreach(_.setLeftActionLabelText(getString(textId)))
    }

  }

  override def onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation = {
    val parent = getParentFragment
    // Apply the workaround only if this is a child fragment, and the parent is being removed.
    if (!enter && parent != null && parent.isRemoving) {
      // This is a workaround for the bug where child fragments disappear when
      // the parent is removed (as all children are first removed from the parent)
      // See https://code.google.com/p/android/issues/detail?id=55228
      returning(new AlphaAnimation(1, 1)) {
        _.setDuration(ViewUtils.getNextAnimationDuration(parent))
      }
    } else super.onCreateAnimation(transit, enter, nextAnim)
  }

  override def onCreateView(inflater: LayoutInflater, viewGroup: ViewGroup, savedInstanceState: Bundle): View =
    inflater.inflate(R.layout.fragment_group_participant, viewGroup, false)

  override def onViewCreated(view: View, @Nullable savedInstanceState: Bundle): Unit = {
    super.onViewCreated(view, savedInstanceState)

    val layoutManager =
      returning(new GridLayoutManager(getContext, getInt(R.integer.participant_column__count))) {
        _.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
          override def getSpanSize(position: Int): Int = participantsAdapter.getSpanSize(position)
        })
      }

    participantsView.foreach { v =>
      v.setAdapter(participantsAdapter)
      v.setLayoutManager(layoutManager)
    }

    participantsView
    topBorder
    footerWrapper
    footerMenu.foreach { fm =>
      fm.setRightActionText(getString(R.string.glyph__more))
      fm.setCallback(footerMenuCallback)
    }
  }
}

object GroupParticipantsFragment {
  val TAG: String = classOf[GroupParticipantsFragment].getName

  def newInstance(): GroupParticipantsFragment =
    new GroupParticipantsFragment

  trait Container {

    def getCurrentPickerDestination: IPickUserController.Destination
  }

}
