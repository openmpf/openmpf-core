#! /usr/bin/env python

import os
import sys
import time


def main():
    if len(sys.argv) > 4:
        write_count_file(sys.argv[4])

    process_type = sys.argv[1]
    ini_path = sys.argv[2]
    stop_delay = 3 if len(sys.argv) < 4 else float(sys.argv[3])

    print 'This is the fake:', process_type

    print 'My ini file is at:', ini_path
    if not os.path.isfile(ini_path):
        sys.exit('Error: ini file does not exist')


    print 'The stop delay is: %s seconds' % stop_delay
    sys.stdout.flush()

    if process_type == 'FrameReader':
        run_frame_reader(stop_delay)
    elif process_type == 'VideoWriter':
        run_video_writer()
    elif process_type == 'Component':
        run_component()
    else:
        sys.exit('Unknown process type: ' + process_type)

    time.sleep(stop_delay)
    print process_type, 'is exiting'



def write_count_file(count_file):
    print 'Writing to count file:', count_file
    with open(count_file, 'a') as f:
        f.write('x')



def run_frame_reader(stop_delay):
    command = wait_for_input(['quit', 'pause'])
    time.sleep(stop_delay)

    if command == 'quit':
        sys.exit('quit called before pause')

    print 'FrameReader is preparing for shutdown'
    wait_for_input(['quit'])


def run_video_writer():
    wait_for_input(['quit'])


def run_component():
    wait_for_input(['quit'])



def wait_for_input(input_content):
    while True:
        line = raw_input()
        print sys.argv[1], 'received:', line
        sys.stdout.flush()
        if line in input_content:
            return line



if __name__ == '__main__':
    main()

