package top.leoblog.myauthenticator.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import top.leoblog.myauthenticator.R

/**
 * Authenticator 主页 Fragment — 显示已加入的账户列表
 * 当前为空状态，后续扩展
 */
class AuthenticatorFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_authenticator, container, false)
    }
}
