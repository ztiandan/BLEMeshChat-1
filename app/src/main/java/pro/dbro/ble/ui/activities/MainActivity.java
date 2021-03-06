package pro.dbro.ble.ui.activities;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.widget.DrawerLayout;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import im.delight.android.identicons.SymmetricIdenticon;
import pro.dbro.ble.ChatService;
import pro.dbro.ble.R;
import pro.dbro.ble.data.model.Peer;
import pro.dbro.ble.transport.ble.BLEUtil;
import pro.dbro.ble.ui.fragment.MessageListFragment;

public class MainActivity extends Activity implements ServiceConnection, LogConsumer {

    public static final String TAG = "MainActivity";

    public ChatService.ChatServiceBinder mChatServiceBinder;
    private boolean mServiceBound = false;  // Are we bound to the ChatService?
    private boolean mBluetoothReceiverRegistered = false; // Are we registered for Bluetooth status broadcasts?

    //private PeerListFragment mPeerListFragment;
    private MessageListFragment mMessageListFragment;
    private Peer mUserIdentity;

    private AlertDialog mBluetoothEnableDialog;

    private TextView mLogView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mLogView = (TextView) findViewById(R.id.log);
        mLogView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                mLogView.setText("");
                return false;
            }
        });

        ((Switch) findViewById(R.id.onlineSwitch)).setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
                if (!checked) {
                    if (mServiceBound) {
                        mChatServiceBinder.shutdown();
                        mServiceBound = false;
                    }
                } else {
                    if (!mServiceBound) {
                        startAndBindToService();
                    }
                }
            }
        });

        DrawerLayout drawerLayout = (DrawerLayout) findViewById(R.id.my_drawer_layout);
        drawerLayout.setStatusBarBackground(R.color.primaryDark);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!mServiceBound) {
            startAndBindToService();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mChatServiceBinder != null) {
            mChatServiceBinder.setActivityReceivingMessages(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mChatServiceBinder != null) {
            mChatServiceBinder.setActivityReceivingMessages(false);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (!mServiceBound) {
            unBindService();
        }
    }

    private void startAndBindToService() {
        Log.i(TAG, "Starting service");
        Intent intent = new Intent(this, ChatService.class);
        startService(intent);
        bindService(intent, this, 0);
    }

    private void unBindService() {
        unbindService(this);
    }

    /**
     * Evaluate preconditions to showing MessageListFragment.
     * e.g: Is Bluetooth enabled? Is a primary identity created
     */
    private void checkChatPreconditions() {
        if (!BLEUtil.isBluetoothEnabled(this)) {
            // Bluetooth is not Enabled.
            // await result in OnActivityResult
            registerBroadcastReceiver();
            showEnableBluetoothDialog();
        } else {
            // Bluetooth Enabled, Check if primary identity is created

            mUserIdentity = mChatServiceBinder.getChatApp().getPrimaryIdentity();
            if (mUserIdentity == null) {
                Util.showWelcomeDialog(mChatServiceBinder.getChatApp(), this, new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        mChatServiceBinder.connect();
                        mUserIdentity = mChatServiceBinder.getChatApp().getPrimaryIdentity();
                        revealChatViews();
                    }
                });
            } else {
                mChatServiceBinder.connect();
                Log.i(TAG, "showing messageListFragment");
                revealChatViews();
            }
        }
    }

    /**
     * Prompt the user to enable Bluetooth
     */
    private void showEnableBluetoothDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enable Bluetooth")
                .setMessage("This app requires Bluetooth on to function. May we enable Bluetooth?")
                .setPositiveButton("Enable Bluetooth", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        mBluetoothEnableDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        mBluetoothEnableDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        ((TextView) mBluetoothEnableDialog.findViewById(android.R.id.message)).setText("Enabling...");
                        BLEUtil.getManager(MainActivity.this).getAdapter().enable();
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        MainActivity.this.finish();
                    }
                });
        builder.setCancelable(false);
        mBluetoothEnableDialog = builder.create();

        mBluetoothEnableDialog.show();
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        this.registerReceiver(mBluetoothBroadcastReceiver, filter);
        mBluetoothReceiverRegistered = true;
    }

    /**
     * Adds the message list fragment and populates
     * the profile navigation drawer with the user profile
     */
    private void revealChatViews() {
        mMessageListFragment = new MessageListFragment();
        getFragmentManager().beginTransaction()
                .add(R.id.container, mMessageListFragment)
                .commit();

        ((SymmetricIdenticon) findViewById(R.id.profileIdenticon)).show(new String(mUserIdentity.getPublicKey()));
        ((TextView) findViewById(R.id.profileName)).setText(mUserIdentity.getAlias());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mBluetoothReceiverRegistered)
            this.unregisterReceiver(mBluetoothBroadcastReceiver);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

//    @Override
//    public void onFragmentInteraction(Uri uri) {
//
//    }

    private final BroadcastReceiver mBluetoothBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        break;
                    case BluetoothAdapter.STATE_ON:
                        if (mBluetoothEnableDialog != null && mBluetoothEnableDialog.isShowing()) {
                            mBluetoothEnableDialog.dismiss();
                        }
                        checkChatPreconditions();
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        break;
                }
            }
        }
    };

    /** ServiceConnection interface */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        mChatServiceBinder = (ChatService.ChatServiceBinder) iBinder;
        mServiceBound = true;
        Log.i(TAG, "Bound to service");
        checkChatPreconditions();

        mChatServiceBinder.getChatApp().setLogConsumer(this);
        mChatServiceBinder.setActivityReceivingMessages(true);

        ((Switch) findViewById(R.id.onlineSwitch)).setChecked(true);
        findViewById(R.id.onlineSwitch).setEnabled(true);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        Log.i(TAG, "Unbound from service");
        mChatServiceBinder = null;
        mServiceBound = false;
        ((Switch) findViewById(R.id.onlineSwitch)).setChecked(false);
    }

    /** LogConsumer interface */

    @Override
    public void onLogEvent(final String event) {
        /*
        mLogView.post(new Runnable() {
            @Override
            public void run() {
                mLogView.append(event + "\n");

            }
        });
        */
    }
}
