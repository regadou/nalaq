#!/bin/sh

kotlin -cp $(ls $(pwd)/build/nalaq-0.1-SNAPSHOT/lib/*.jar|xargs|tr ' ' :) $@

