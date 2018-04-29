/***************************************************************************************
 * Copyright (C) 2018 by Sina Steiner. All Rights Reserved.
 * Software is distributed "AS IS" without warranty of any kind,
 * either express or implied. Use at your own risk!
 *
 * This is a MultiTap-Delay. The basic idea is as follows:
 * The input signal is written into a buffer and is then read out at multiple locations defined by pointers which "lag" after the input.
 * The output of each tap is also written into a feedback buffer which is scaled by the feedback factor and fed back into the input.
 * Each tap has its own delay time, feedback and gain factor, which can be set by sending a message to the mxj object (see code or examples in the Maxpatch)
 *
 ****************************************************************************************/

import com.cycling74.max.*;
import com.cycling74.msp.*;

public class MultiTapDelay extends MSPPerformer
{
    private float sampleRate = 96000;									//Some basic initialization and declrearing all the needed Variables that are shared by all the taps...//
    private int bufferLength = 132301;									// If you want longer delay times change this value!//
    private float dryGain = 0.5f, wetGain = 0.5f, tempOutput;
    private int  pointerIn = 0, maxTapAmount = 100, tapAmount = 4;
    private float[]inBuffer = new float[bufferLength];					// this is the Buffer, where all the taps read from.//
    private DelayTap[] taps = new DelayTap[maxTapAmount];				// an array with DelayTap objects is created (default maximum = 100) //

    /*---------------------------------- Defining the DelayTap class ----------------------------------*/
    public class DelayTap
    {
        private int delayTime = 0, pointerOut = 0;
        private float tapGain = 0, feedback = 0;
        private float[]buffer;
        private float[]feedbackBuffer = new float[bufferLength];

        public DelayTap(float[] inBuffer)		//When a Tap-object is created it starts to reference the shared input buffer
        {
            buffer = inBuffer;
        }

        public float processSignal()			//This function returns the delayed output of the tap + feedback, if feedback > 0
        {
            pointerOut++;
            if (pointerOut >= bufferLength) pointerOut -= bufferLength;
            feedbackBuffer[pointerIn] = feedback * (buffer[pointerOut] + feedbackBuffer[pointerOut]);
            return tapGain*(buffer[pointerOut] + feedbackBuffer[pointerOut]);
        }

        // These Functions are called, when a setParameters message is sent to the mxj (see further below)//
        public void setDelayTime(float input)
        {
            delayTime = (int)(input*(sampleRate/1000));		//inlet is in ms, so we calculate delayTime in samples
            if(delayTime > (bufferLength - 1))				// no delayTime larger than bufferLength allowed!
            {
                delayTime = bufferLength -1;
                post("Das Delay darf maximal " + (int)((bufferLength-1)/(sampleRate/1000)) + "ms sein.");
            }
            if(delayTime < 0)								// no negative delayTime allowed!
            {
                delayTime = 0;
                post("Das Delay muss positiv sein!");
            }
            pointerOut = (pointerIn - delayTime);

            // check that pointerOut doesn't become negative when delayTime is larger than the current value of pointerIn
            if(pointerOut < 0) pointerOut += bufferLength;
        }

        public void setTapGain(float input)
        {
            tapGain = input;
            if(tapGain < 0)		// no negative gain allowed!
            {
                tapGain = 0;
                post("Das Gain muss positiv sein!");
            }
        }

        public void setFeedback(float input)
        {
            feedback = input;
            if(feedback < 0)		// no negative feedback allowed!
            {
                feedback = 0;
                post("Das Feedback muss positiv sein!");
            }
            if(feedback > 1)		// for now we don't want it to explode!
            {
                feedback = 1;
                post("Feedback > 1 macht unter Umständen Dinge kaputt. Deshalb ist es hier nicht erlaubt.");
            }
        }
    }
    /*----------------------------------------------------------------------------------------------------------------*/

    private static final String[] INLET_ASSIST = new String[]{
            "input (sig)", "number of taps", "dry signal gain", "wet signal gain", "Set tap parameters with message calling 'setParameters'"
    };
    private static final String[] OUTLET_ASSIST = new String[]{
            "output (sig)"
    };


    public MultiTapDelay()
    {
        declareInlets(new int[]{SIGNAL, DataTypes.INT, DataTypes.FLOAT, DataTypes.FLOAT, DataTypes.FLOAT});
        declareOutlets(new int[]{SIGNAL, DataTypes.ALL});

        setInletAssist(INLET_ASSIST);
        setOutletAssist(OUTLET_ASSIST);

        for(int i = 0; i < maxTapAmount; i++)	//initializing DelayTap Objects when mxj is created
        {
            taps[i] = new DelayTap(inBuffer);
        }
    }

    public void dspsetup(MSPSignal[] ins, MSPSignal[] outs)
    {
		/*Lieber Martin, ich habe herausgefunden, wie man die Vektorgrösse, Samplingrate etc. abfragen kann!
		Es sind Attribute der in- und Outputvektoren. Die dspsetup-Funktion wird jedesmal aufgerufen, wenn das
		Audio im Maxpatch gestartet wird; sie ist also der perfekte Ort um diese Abfrage zu machen.*/
        sampleRate = (int)ins[0].sr;
    }

    /*------------------------------- These functions process the user input ----------------------- */

    public void inlet(int i)
    {
        if(i < 0)
        {
            i = 0;
            post("Wenn du darüber nachdenkst, machen negative Werte hier keinen Sinn!");
            return;
        }

        if(getInlet() == 1)
        {
            pleaseStop();

            if(i > tapAmount)
            {
                if(i > 100)
                {
                    i = 100;
                    post("Wenn du mehr als 100 Taps willst, gibt es jetzt das neue Premium Abo für nur 9.99.-!");
                }

                for(int j = tapAmount ; j < i; j++) 			//We need to make sure that when we delete taps and then add them again their pointerOut is reset!
                {
                    taps[j].setDelayTime( taps[j].delayTime*1000/sampleRate);
                    if(taps[j].tapGain != 0)
                    {
                        post("Tap_" + (j + 1) + "_dly: " + taps[j].delayTime + " samples");
                        post("Tap_" + (j + 1) + "_fb: " + taps[j].feedback);
                        post("Tap_" + (j + 1) + "_gain: " + taps[j].tapGain);
                    }
                }
            }
            tapAmount = i;
        }

        if(getInlet() == 2) dryGain = (float)i/157;			//need to scale because of the way gain objects work
        if(getInlet() == 3) wetGain = (float)i/157;
    }


    public void setParameters(float[] list)
    {
        if(list.length != 4)
        {
            post("You need to enter the tapNumber + 3 and only 3 parameters");
            return;
        }
        int tapID = (int)list[0] - 1;
        if(tapID > 99)
        {
            post("Wenn du mehr als 100 Taps willst, gibt es jetzt das neue Premium Abo für nur 9.99.-!");
            return;
        }
        if(tapID >= tapAmount)
        {
            post("Tap number " + (tapID +1) + " parameters are set, but you won't hear it, because your number of taps is set too low.");
        }

        taps[tapID].setDelayTime(list[1]);
        taps[tapID].setFeedback(list[2]);
        taps[tapID].setTapGain(list[3]);

        post("Tap_" + (tapID + 1) + "_dly: " + taps[tapID].delayTime + " samples");
        post("Tap_" + (tapID + 1) + "_fb: " + taps[tapID].feedback);
        post("Tap_" + (tapID + 1) + "_gain: " + taps[tapID].tapGain);

    }
    /*-------------------------------------------------------------------------------------------- */

    public void pleaseStop()							//resets input and feedback buffer (stops audio)
    {
        for(int i = 0; i < maxTapAmount; i++)
        {
            for(int j = 0; j < bufferLength; j++)
            {
                inBuffer[j] = 0;
                taps[i].feedbackBuffer[j] = 0;
            }
        }
    }

    public void reset()									//resets mxj to default state
    {
        pleaseStop();
        wetGain = 0.5f;
        dryGain = 0.5f;
        tapAmount = 4;
        for(int j = 0; j < maxTapAmount; j++)
        {
            taps[j].delayTime = 0;
            taps[j].feedback = 0;
            taps[j].tapGain = 0;
        }
        outletBang(1);
    }

    public void perform(MSPSignal[] ins, MSPSignal[] outs)
    {


        int i;
        float[] in = ins[0].vec;
        float[] out = outs[0].vec;
        for(i = 0; i < in.length;i++)
        {
            pointerIn++;
            if (pointerIn >= bufferLength) pointerIn -= bufferLength;
            inBuffer[pointerIn] = in[i];								//reading the input signal into the input buffer

            for(int j = 0; j < tapAmount; j++)							//going through all the taps and adding up their ouput
            {
                tempOutput += taps[j].processSignal();
            }
            out[i] = wetGain*tempOutput + dryGain*in[i];				//combining delayed signal of the taps with dry signal
            tempOutput = 0;

        }
    }
}
