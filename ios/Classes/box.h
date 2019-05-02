//
//  box.h
//  tflite_camera_example
//
//  Created by Yonatan Naor on 18/03/2019.
//  Copyright © 2019 Google. All rights reserved.
//

#ifndef box_h
#define box_h

#import <Foundation/Foundation.h>

@interface Box : NSObject

@property float x;
@property float y;
@property float width;
@property float height;
@property float confidence;
@property float classValue;
@property float detectedClass;
@property NSString* className;

@end

#endif /* box_h */
