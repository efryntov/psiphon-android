/*
 * Copyright (c) 2013, Psiphon Inc.
 * All rights reserved.
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
 *
 */

package com.psiphon3.psiphonlibrary;

import java.util.List;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Pair;

public class TunnelService extends Service
{
    private TunnelCore m_Core = new TunnelCore(this, this);

    public class LocalBinder extends Binder
    {
        public TunnelService getService()
        {
            return TunnelService.this;
        }
    }
    private final IBinder m_binder = new LocalBinder();
    
    @Override
    public IBinder onBind(Intent intent)
    {
        return m_binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        return m_Core.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate()
    {
        m_Core.onCreate();
    }

    @Override
    public void onDestroy()
    {
        m_Core.onDestroy();
    }

    public void setEventsInterface(IEvents eventsInterface)
    {
        m_Core.setEventsInterface(eventsInterface);
    }
    
    public ServerInterface getServerInterface()
    {
        return m_Core.getServerInterface();
    }
    
    public void setExtraAuthParams(List<Pair<String,String>> extraAuthParams)
    {
        m_Core.setExtraAuthParams(extraAuthParams);
    }
}
